#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "ggml-backend.h"
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <ios>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
double milliseconds(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
void log_message(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR)
        __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                            "CirvueVlm", text);
}
void initialize() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_message, nullptr);
        mtmd_helper_log_set(log_message, nullptr);
        // Upstream's directory scan cannot enumerate libraries inside an APK.
        // Probe the same Android variants by soname; each score function is built
        // for baseline ARM and checks CPU features before any optimized code runs.
        const char * variants[] = {"android_armv8.0_1", "android_armv8.2_1",
            "android_armv8.2_2", "android_armv8.6_1", "android_armv9.0_1",
            "android_armv9.2_1", "android_armv9.2_2"};
        int best = 0;
        std::string selected;
        for (const char * variant : variants) {
            std::string name = std::string("libggml-cpu-") + variant + ".so";
            void * lib = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (!lib) continue;
            auto score = reinterpret_cast<int (*)()>(dlsym(lib, "ggml_backend_score"));
            int value = score ? score() : 0;
            if (value > best) { best = value; selected = name; }
            dlclose(lib);
        }
        if (selected.empty() || !ggml_backend_load(selected.c_str()))
            throw std::runtime_error("Cannot load a compatible CPU backend");
        llama_backend_init();
    });
}
struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * vision = nullptr;
    std::atomic<bool> cancelled{false};
    ~Engine() {
        if (vision) mtmd_free(vision);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
bool abort_eval(void * data) { return static_cast<Engine *>(data)->cancelled.load(); }
std::unique_ptr<Engine> load(const std::string & model, const std::string & projector, int threads,
                             int image_tokens) {
    initialize();
    auto result = std::make_unique<Engine>();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    result->model = llama_model_load_from_file(model.c_str(), mp);
    if (!result->model) throw std::ios_base::failure("Cannot load language model GGUF");
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.abort_callback = abort_eval;
    cp.abort_callback_data = result.get();
    result->context = llama_init_from_model(result->model, cp);
    if (!result->context) throw std::runtime_error("Cannot create language model context");
    auto vp = mtmd_context_params_default();
    vp.use_gpu = false;
    vp.n_threads = threads;
    vp.print_timings = false;
    vp.warmup = false;
    // Only encoders with a variable image size read this limit. Others ignore it.
    if (image_tokens > 0) vp.image_max_tokens = image_tokens;
    result->vision = mtmd_init_from_file(projector.c_str(), result->model, vp);
    if (!result->vision) throw std::ios_base::failure("Cannot load vision projector GGUF");
    if (!mtmd_support_vision(result->vision)) throw std::runtime_error("Projector does not support images");
    return result;
}
// Formats one user turn and opens the model turn.
std::string format_turn(const llama_model * model, const std::string & user) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) throw std::runtime_error("Model has no chat template");
    llama_chat_message message{"user", user.c_str()};
    int size = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    if (size < 0) {
        // Without a Jinja engine, llama.cpp formats only the templates it knows by name. Gemma 4
        // is not one of them. Its template gives a single user turn, with thinking off, this form.
        if (!std::strstr(tmpl, "<|turn>")) throw std::runtime_error("Model chat template is unsupported");
        return "<|turn>user\n" + user + "<turn|>\n<|turn>model\n";
    }
    std::string formatted(size, '\0');
    if (llama_chat_apply_template(tmpl, &message, 1, true, formatted.data(), size + 1) != size)
        throw std::runtime_error("Cannot format model prompt");
    return formatted;
}
struct Metrics { double prompt_ms = 0, generation_ms = 0; int tokens = 0; };
std::string describe(Engine & e, const uint8_t * rgb, uint32_t width, uint32_t height,
                     const std::string & prompt, int max_tokens, Metrics * metrics = nullptr) {
    auto memory = llama_get_memory(e.context);
    llama_memory_clear(memory, true);
    struct Clear { llama_memory_t memory; ~Clear() { llama_memory_clear(memory, true); } } clear{memory};
    mtmd::bitmap_ptr bitmap(mtmd_bitmap_init(width, height, rgb));
    if (!bitmap) throw std::runtime_error("Cannot create image input");
    std::string formatted = format_turn(e.model, std::string(mtmd_default_marker()) + "\n" + prompt);
    mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
    mtmd_input_text text{formatted.data(), formatted.size(), true, true};
    const mtmd_bitmap * images[] = {bitmap.get()};
    if (mtmd_tokenize(e.vision, chunks.get(), &text, images, 1) != 0)
        throw std::runtime_error("Cannot tokenize image and prompt");
    if (mtmd_helper_get_n_tokens(chunks.get()) + max_tokens > llama_n_ctx(e.context))
        throw std::runtime_error("Image, prompt and output exceed model context");
    if (e.cancelled.load()) return {};
    llama_pos past = 0;
    auto start = Clock::now();
    int status = mtmd_helper_eval_chunks(e.vision, e.context, chunks.get(), 0, 0, 512, true, &past);
    if (metrics) metrics->prompt_ms = milliseconds(start);
    if (e.cancelled.load()) return {};
    if (status != 0) throw std::runtime_error("Image or prompt evaluation failed");
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    const auto * vocab = llama_model_get_vocab(e.model);
    std::string output;
    start = Clock::now();
    for (int i = 0; i < max_tokens && !e.cancelled.load(); ++i) {
        llama_token token = llama_sampler_sample(sampler.get(), e.context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(128);
        int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (n < 0) throw std::runtime_error("Cannot decode output token");
        output.append(piece.data(), n);
        if (metrics) ++metrics->tokens;
        if (i + 1 == max_tokens) break;
        auto batch = llama_batch_get_one(&token, 1);
        status = llama_decode(e.context, batch);
        if (e.cancelled.load()) break;
        if (status != 0) throw std::runtime_error("Output token evaluation failed");
    }
    if (metrics) metrics->generation_ms = milliseconds(start);
    return output;
}
} // namespace

#ifndef CIRVUE_VLM_BENCHMARK
#include <jni.h>
#include <android/bitmap.h>
namespace {
void throw_java(JNIEnv * env, const std::exception & error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(
        dynamic_cast<const std::ios_base::failure *>(&error) ? "java/io/IOException" :
        "java/lang/IllegalStateException"), error.what());
}
std::string bytes(JNIEnv * env, jbyteArray array) {
    std::string result(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read input bytes");
    if (result.find('\0') != std::string::npos) throw std::runtime_error("Input contains a null byte");
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_app_cirvue_vlm_VisionModel_nativeLoad(
    JNIEnv * env, jobject, jbyteArray model, jbyteArray projector, jint threads, jint image_tokens) {
    try { return reinterpret_cast<jlong>(load(bytes(env, model), bytes(env, projector), threads, image_tokens).release()); }
    catch (const std::exception & error) { throw_java(env, error); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_cirvue_vlm_VisionModel_nativeDescribe(
    JNIEnv * env, jobject, jlong handle, jobject bitmap, jbyteArray prompt, jint max_tokens) {
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height)
            throw std::runtime_error("Image must be a software ARGB_8888 bitmap");
        std::vector<uint8_t> rgb(static_cast<size_t>(info.width) * info.height * 3);
        void * pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot read bitmap pixels");
        for (uint32_t y = 0; y < info.height; ++y) {
            const auto * row = static_cast<const uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride;
            for (uint32_t x = 0; x < info.width; ++x)
                for (int c = 0; c < 3; ++c) rgb[(static_cast<size_t>(y) * info.width + x) * 3 + c] = row[x * 4 + c];
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        std::string output = describe(*reinterpret_cast<Engine *>(handle), rgb.data(), info.width, info.height,
                                      bytes(env, prompt), max_tokens);
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) { throw_java(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_app_cirvue_vlm_VisionModel_nativeSetCancelled(JNIEnv *, jobject, jlong handle, jboolean cancelled) {
    reinterpret_cast<Engine *>(handle)->cancelled.store(cancelled);
}
extern "C" JNIEXPORT void JNICALL Java_app_cirvue_vlm_VisionModel_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
#else
#include <fstream>
#include <iostream>
#include <sys/resource.h>
int main(int argc, char ** argv) {
    try {
        if (argc < 6 || argc > 8)
            throw std::runtime_error("Usage: benchmark model projector rgb width height [image-tokens] [prompt]");
        auto start = Clock::now();
        auto engine = load(argv[1], argv[2], 4, argc > 6 ? std::stoi(argv[6]) : 0);
        double load_ms = milliseconds(start);
        int width = std::stoi(argv[4]), height = std::stoi(argv[5]);
        std::vector<uint8_t> rgb(static_cast<size_t>(width) * height * 3);
        std::ifstream input(argv[3], std::ios::binary);
        if (!input.read(reinterpret_cast<char *>(rgb.data()), rgb.size())) throw std::runtime_error("Cannot read RGB image");
        // Isolated encode probe; describe below still uses mtmd_helper_eval_chunks.
        mtmd::bitmap_ptr bitmap(mtmd_bitmap_init(width, height, rgb.data()));
        mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
        std::string marker = mtmd_default_marker();
        mtmd_input_text text{marker.c_str(), marker.size(), true, true};
        const mtmd_bitmap * images[] = {bitmap.get()};
        if (mtmd_tokenize(engine->vision, chunks.get(), &text, images, 1)) throw std::runtime_error("Probe tokenize failed");
        start = Clock::now();
        for (size_t i = 0; i < mtmd_input_chunks_size(chunks.get()); ++i) {
            const auto * chunk = mtmd_input_chunks_get(chunks.get(), i);
            if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE && mtmd_encode_chunk(engine->vision, chunk))
                throw std::runtime_error("Probe encode failed");
        }
        double encode_ms = milliseconds(start);
        Metrics metrics;
        start = Clock::now();
        auto output = describe(*engine, rgb.data(), width, height, argc > 7 ? argv[7] :
            "Write a short web search query for the main object in this image. Output only the query.", 64, &metrics);
        double total = milliseconds(start);
        rusage usage{}; getrusage(RUSAGE_SELF, &usage);
        std::cout << "load_ms=" << load_ms << " isolated_encode_ms=" << encode_ms
                  << " prompt_ms=" << metrics.prompt_ms << " generation_ms=" << metrics.generation_ms
                  << " tokens=" << metrics.tokens << " tokens_per_second=" << metrics.tokens * 1000 / metrics.generation_ms
                  << " describe_ms=" << total << " peak_rss_kib=" << usage.ru_maxrss << "\noutput=" << output << '\n';
    } catch (const std::exception & error) { std::cerr << error.what() << '\n'; return 1; }
}
#endif
