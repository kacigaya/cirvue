// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Modified for Cirvue: plain Kotlin arrays, axis-aligned connected components,
// single-image recognition, and a restricted inference.yml dictionary reader.
package app.cirvue.ocr

import kotlin.math.*

internal data class Size(val width: Int, val height: Int)
internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun detectionSize(width: Int, height: Int, limit: Int = 64,
                           type: String = "min", maxSide: Int = 4000): Size {
    require(width > 0 && height > 0 && limit > 0 && maxSide >= 32)
    var ratio = when (type) {
        "min" -> max(1.0, limit.toDouble() / min(width, height))
        "max" -> min(1.0, limit.toDouble() / max(width, height))
        "resize_long" -> limit.toDouble() / max(width, height)
        else -> error("Unsupported detection limit type: $type")
    }
    var w = (width * ratio).toInt()
    var h = (height * ratio).toInt()
    if (max(w, h) > maxSide) {
        ratio = maxSide.toDouble() / max(w, h)
        w = (w * ratio).toInt()
        h = (h * ratio).toInt()
    }
    return Size(max(32, (Math.rint(w / 32.0) * 32).toInt()),
        max(32, (Math.rint(h / 32.0) * 32).toInt()))
}

internal fun recognitionSize(width: Int, height: Int): Size {
    require(width > 0 && height > 0)
    return Size(ceil(48.0 * width / height).toInt().coerceIn(1, 3200), 48)
}

/** Only the block sequence of string scalars used by Paddle's inference.yml is supported. */
internal fun parseDictionary(yaml: String): List<String> {
    val lines = yaml.lineSequence().toList()
    val start = lines.indexOfFirst { it.trim() == "character_dict:" }
    require(start >= 0) { "Missing character_dict" }
    val indent = lines[start].takeWhile { it == ' ' }.length
    val chars = mutableListOf("") // CTC blank
    for (line in lines.drop(start + 1)) {
        if (line.isBlank() || line.trimStart().startsWith("#")) continue
        val depth = line.takeWhile { it == ' ' }.length
        if (depth < indent || !line.trimStart().startsWith("- ")) break
        val scalar = line.trimStart().substring(2).trim { it == ' ' || it == '\t' || it == '\r' }
        val value = when {
            scalar.startsWith("'") -> {
                require(scalar.endsWith("'") && scalar.length >= 2)
                scalar.substring(1, scalar.length - 1).replace("''", "'")
            }
            scalar.startsWith('"') -> decodeYamlQuoted(scalar)
            else -> {
                require(scalar.isNotEmpty() && scalar.first() !in "[{}&*!|>" && !scalar.contains(" #")) {
                    "Unsupported dictionary scalar: $scalar"
                }
                scalar
            }
        }
        require(value.isNotEmpty()) { "Empty dictionary entry" }
        chars.add(value)
    }
    require(chars.size > 1) { "Empty character_dict" }
    chars.add(" ")
    return chars
}

private fun decodeYamlQuoted(s: String): String {
    require(s.length >= 2 && s.last() == '"')
    val result = StringBuilder()
    var i = 1
    while (i < s.lastIndex) {
        val c = s[i++]
        if (c != '\\') { result.append(c); continue }
        require(i < s.lastIndex)
        when (val escape = s[i++]) {
            '"', '\\', '/' -> result.append(escape)
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u', 'U', 'x' -> {
                val count = when (escape) { 'u' -> 4; 'U' -> 8; else -> 2 }
                require(i + count <= s.lastIndex)
                val code = s.substring(i, i + count).toInt(16)
                require(Character.isValidCodePoint(code))
                result.appendCodePoint(code)
                i += count
            }
            else -> error("Unsupported YAML escape: $escape")
        }
    }
    return result.toString()
}

internal fun decodeCtc(values: FloatArray, steps: Int, dictionary: List<String>): Pair<String, Float> {
    val classes = dictionary.size
    require(steps >= 0 && values.size.toLong() == steps.toLong() * classes)
    val text = StringBuilder()
    var previous = -1
    var sum = 0.0
    var count = 0
    for (t in 0 until steps) {
        val offset = t * classes
        var best = 0
        for (c in 1 until classes) if (values[offset + c] > values[offset + best]) best = c
        if (best != 0 && best != previous) {
            text.append(dictionary[best])
            sum += values[offset + best]
            count++
        }
        previous = best
    }
    return text.toString() to if (count == 0) 0f else (sum / count).toFloat()
}

internal fun unclip(box: Box, ratio: Float): Box {
    val w = box.right - box.left
    val h = box.bottom - box.top
    require(w > 0 && h > 0 && ratio >= 0)
    val offset = w * h * ratio / (2 * (w + h))
    return Box(box.left - offset, box.top - offset, box.right + offset, box.bottom + offset)
}

/** Axis-aligned boxes replace rotated contours. Fast score includes every pixel in the box. */
internal fun dbBoxes(prob: FloatArray, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int,
                     threshold: Float = .2f, boxThreshold: Float = .45f,
                     unclipRatio: Float = 1.4f, maxCandidates: Int = 3000): List<Box> {
    require(width > 0 && height > 0 && prob.size.toLong() == width.toLong() * height)
    require(sourceWidth > 0 && sourceHeight > 0 && maxCandidates > 0)
    val visited = BooleanArray(prob.size)
    val queue = IntArray(prob.size)
    val result = mutableListOf<Box>()
    var candidates = 0
    for (seed in prob.indices) {
        if (visited[seed] || prob[seed] <= threshold || !prob[seed].isFinite()) continue
        if (candidates++ >= maxCandidates) break
        var head = 0
        var tail = 1
        queue[0] = seed
        visited[seed] = true
        var left = seed % width
        var right = left
        var top = seed / width
        var bottom = top
        while (head < tail) {
            val p = queue[head++]
            val x = p % width
            val y = p / width
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
            for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                    val n = ny * width + nx
                    if (!visited[n] && prob[n].isFinite() && prob[n] > threshold) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }
        }
        if (min(right - left, bottom - top) < 3) continue
        var score = 0.0
        for (y in top..bottom) for (x in left..right) score += prob[y * width + x]
        score /= (right - left + 1).toDouble() * (bottom - top + 1)
        if (!score.isFinite() || score < boxThreshold) continue
        val b = unclip(Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), unclipRatio)
        if (min(b.right - b.left, b.bottom - b.top) < 5) continue
        fun sx(v: Float) = Math.rint(v.toDouble() * sourceWidth / width).toFloat().coerceIn(0f, sourceWidth.toFloat())
        fun sy(v: Float) = Math.rint(v.toDouble() * sourceHeight / height).toFloat().coerceIn(0f, sourceHeight.toFloat())
        val scaled = Box(sx(b.left), sy(b.top), sx(b.right), sy(b.bottom))
        if (scaled.right - scaled.left > 3 && scaled.bottom - scaled.top > 3) result.add(scaled)
    }
    return sortBoxes(result)
}

internal fun sortBoxes(boxes: List<Box>, tolerance: Float = 10f): List<Box> {
    val sorted = boxes.sortedWith(compareBy<Box> { it.top }.thenBy { it.left }).toMutableList()
    for (i in 1 until sorted.size) {
        var j = i
        while (j > 0 && abs(sorted[j].top - sorted[j - 1].top) < tolerance && sorted[j].left < sorted[j - 1].left) {
            val tmp = sorted[j]; sorted[j] = sorted[j - 1]; sorted[j - 1] = tmp
            j--
        }
    }
    return sorted
}

/** Half-pixel bilinear sampling, CHW layout. Detection uses BGR; recognition uses RGB. */
internal fun imageTensor(pixels: IntArray, width: Int, height: Int, size: Size,
                         detection: Boolean, paddedWidth: Int = size.width): FloatArray {
    require(pixels.size == width * height && paddedWidth >= size.width)
    val plane = paddedWidth * size.height
    val data = FloatArray(3 * plane) // Normalized zero padding for recognition.
    val mean = floatArrayOf(.485f, .456f, .406f)
    val std = floatArrayOf(.229f, .224f, .225f)
    for (y in 0 until size.height) {
        val sy = ((y + .5) * height / size.height - .5).coerceIn(0.0, height - 1.0)
        val y0 = sy.toInt(); val y1 = min(y0 + 1, height - 1); val fy = sy - y0
        for (x in 0 until size.width) {
            val sx = ((x + .5) * width / size.width - .5).coerceIn(0.0, width - 1.0)
            val x0 = sx.toInt(); val x1 = min(x0 + 1, width - 1); val fx = sx - x0
            for (c in 0..2) {
                val shift = if (detection) c * 8 else (2 - c) * 8
                fun channel(px: Int) = (px ushr shift) and 255
                val a = channel(pixels[y0 * width + x0]) * (1 - fx) + channel(pixels[y0 * width + x1]) * fx
                val b = channel(pixels[y1 * width + x0]) * (1 - fx) + channel(pixels[y1 * width + x1]) * fx
                val value = (a * (1 - fy) + b * fy).roundToInt() / 255f
                data[c * plane + y * paddedWidth + x] = if (detection) (value - mean[c]) / std[c] else (value - .5f) / .5f
            }
        }
    }
    return data
}
