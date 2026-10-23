package com.irio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

internal data class ThinkingSliderGeometry(val thumbX: Float, val fillStart: Float, val fillWidth: Float)

/** Track fill and thumb share exactly the same travel, including the radius at each end. */
internal fun thinkingSliderGeometry(width: Float, radius: Float, fraction: Float, rtl: Boolean): ThinkingSliderGeometry {
    val progress = fraction.coerceIn(0f, 1f)
    val inset = radius.coerceAtMost(width / 2f)
    val center = inset + (width - inset * 2f) * progress
    val fill = when (progress) {
        0f -> 0f
        1f -> width
        else -> center
    }
    return if (rtl) ThinkingSliderGeometry(width - center, width - fill, fill)
        else ThinkingSliderGeometry(center, 0f, fill)
}

internal data class ThinkingIndicatorGeometry(
    val bodyTop: Float, val radius: Float, val tipX: Float, val rootStart: Float, val rootEnd: Float, val rootBottom: Float,
)

/** Keep the original straight, symmetric pointer; extend its hidden root into the capsule. */
internal fun thinkingIndicatorGeometry(width: Float, height: Float, tipX: Float, bodyTop: Float, shoulder: Float): ThinkingIndicatorGeometry {
    val top = bodyTop.coerceIn(0f, height)
    val radius = min((height - top) / 2f, width / 2f)
    val center = tipX.coerceIn(0f, width)
    val halfRoot = min(shoulder, min(center, width - center))
    val start = center - halfRoot
    val end = center + halfRoot
    fun capsuleTop(x: Float): Float {
        val dx = when {
            x < radius -> radius - x
            x > width - radius -> x - (width - radius)
            else -> 0f
        }
        return top + radius - sqrt((radius * radius - dx * dx).coerceAtLeast(0f))
    }
    val depth = max(capsuleTop(start), capsuleTop(end)) + shoulder * 0.2f
    return ThinkingIndicatorGeometry(top, radius, center, start, end, depth)
}

internal data class ThinkingRollState(val index: Int, val direction: Int = 1) {
    fun moveTo(next: Int): ThinkingRollState = if (next == index) this else ThinkingRollState(next, if (next > index) 1 else -1)
}

internal data class ThinkingParticleFrame(
    val x: Float, val y: Float, val opacity: Float, val scale: Float, val trail: Float, val star: Boolean,
)

/** Each birth gets new random properties; only an invisible particle can change trajectory. */
internal class ThinkingParticleField(seed: Int, val count: Int) {
    private val random = Random(seed)
    private data class Particle(
        val born: Float, val duration: Float, val y: Float, val wave: Float, val wavePhase: Float,
        val scale: Float, val trail: Float, val brightness: Float, val star: Boolean,
    )
    private fun spawn(born: Float) = Particle(
        born, random.nextFloat() * 1.1f + 1.05f, random.nextFloat() * 0.7f + 0.15f,
        random.nextFloat() * 0.055f + 0.02f, random.nextFloat() * 2f,
        random.nextFloat() * 0.85f + 0.65f, random.nextFloat() * 8f + 4f,
        random.nextFloat() * 0.45f + 0.5f, random.nextFloat() < 0.25f,
    )
    private val particles = Array(count) {
        val particle = spawn(0f)
        particle.copy(born = -random.nextFloat() * particle.duration)
    }
    fun sample(index: Int, seconds: Float): ThinkingParticleFrame {
        var particle = particles[index]
        while (seconds >= particle.born + particle.duration) {
            particle = spawn(particle.born + particle.duration)
            particles[index] = particle
        }
        val life = ((seconds - particle.born) / particle.duration).coerceIn(0f, 1f)
        return ThinkingParticleFrame(
            life * 1.3f - 0.15f,
            particle.y + sin((life + particle.wavePhase) * PI * 2f).toFloat() * particle.wave,
            sin(life * PI).toFloat().coerceAtLeast(0f) * particle.brightness,
            particle.scale, particle.trail, particle.star,
        )
    }
}

/** Immediate fast arrival with a soft, decelerating finish. */
internal fun modelSwitcherExtension(phase: Float): Float {
    return IrioEaseOut.transform(phase.coerceIn(0f, 1f))
}

/** The non-overlapping ranges fade through invisibility in either direction, including reversals. */
internal fun modelSwitcherListOpacity(extension: Float): Float =
    modelSwitcherFadeOpacity(extension, start = 0.27f, end = 0.5f)

internal fun modelSwitcherCompactOpacity(extension: Float): Float =
    1f - modelSwitcherFadeOpacity(extension, start = 0.04f, end = 0.23f)

private fun modelSwitcherFadeOpacity(extension: Float, start: Float, end: Float): Float = when {
    extension <= start -> 0f
    extension >= end -> 1f
    else -> IrioEaseInOut.transform((extension - start) / (end - start))
}

internal data class ThinkingGlyphFrame(val index: Int, val character: String, val x: Float, val y: Float = 0f, val alpha: Float = 1f)

/** [delay] is the fraction of the shared timeline this glyph waits before it starts moving. */
internal data class ThinkingGlyphRun(val start: ThinkingGlyphFrame, val end: ThinkingGlyphFrame, val delay: Float = 0f) {
    fun frame(progress: Float): ThinkingGlyphFrame {
        val p = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
        return start.copy(
            x = start.x + (end.x - start.x) * p,
            y = start.y + (end.y - start.y) * p,
            alpha = start.alpha + (end.alpha - start.alpha) * p,
        )
    }
}

/**
 * 0..1 blur envelope for the whole label: sharp at rest, softest while the moving letters are mid-roll.
 * Haze blurs a node as one unit, so the letters' individual bell curves are averaged into a single wave.
 */
internal fun thinkingBlurFraction(runs: List<ThinkingGlyphRun>, progress: Float): Float {
    val moving = runs.filter { it.start != it.end }
    if (moving.isEmpty()) return 0f
    return moving.sumOf { run ->
        val p = ((progress - run.delay) / (1f - run.delay)).coerceIn(0f, 1f)
        sin(p * PI).toDouble()
    }.toFloat() / moving.size
}

/**
 * Every changed character rolls in [direction]; interruptions start at the visible frame.
 * [stagger] spreads glyph start times left to right so each letter jumps on its own.
 * A visible glyph is reused only when settled or already on this roll's entry side; one on the exit
 * side keeps leaving, otherwise it would be dragged back against the roll direction.
 */
internal fun thinkingGlyphRuns(
    previous: List<ThinkingGlyphFrame>, target: List<ThinkingGlyphFrame>, direction: Int, stagger: Float = 0f,
): List<ThinkingGlyphRun> {
    val sign = if (direction < 0) -1f else 1f
    val span = stagger.coerceIn(0f, 0.9f)
    fun delayOf(index: Int, count: Int) = if (count <= 1) 0f else span * index / (count - 1)
    val remaining = previous.filter { it.alpha > 0.04f }.toMutableList()
    val incoming = target.map { glyph ->
        val match = remaining.filter {
            it.index == glyph.index && it.character == glyph.character && (it.y == 0f || it.y * sign > 0f)
        }.maxByOrNull { it.alpha }
        if (match != null) {
            remaining.remove(match)
            ThinkingGlyphRun(match, glyph)
        } else ThinkingGlyphRun(glyph.copy(y = sign, alpha = 0f), glyph, delayOf(glyph.index, target.size))
    }
    val outgoing = remaining.map { glyph ->
        ThinkingGlyphRun(glyph, glyph.copy(y = glyph.y - sign, alpha = 0f), delayOf(glyph.index, previous.size))
    }
    return outgoing + incoming
}
