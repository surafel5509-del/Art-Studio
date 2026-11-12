package com.artforge.mobile.engine.animation

import com.artforge.mobile.core.model.Bone
import com.artforge.mobile.core.model.Pose
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Forward-kinematics rig for cut-out character animation.
 *
 * Bones form a tree; each bone's world transform is its parent's transform composed
 * with the bone's own rest offset, rotation and scale. The solver also provides a
 * two-bone analytic inverse-kinematics helper for limbs, and pose blending for the
 * pose library.
 */
object CharacterRig {

    data class BoneWorld(
        val boneId: String,
        val x: Float,
        val y: Float,
        val worldAngle: Float,
        val worldScale: Float,
        val length: Float,
    )

    /** Resolves world transforms for every bone in depth-first order. */
    fun solve(bones: List<Bone>): Map<String, BoneWorld> {
        val byId = bones.associateBy { it.id }
        val out = LinkedHashMap<String, BoneWorld>()
        val visiting = HashSet<String>()

        fun resolve(bone: Bone): BoneWorld {
            out[bone.id]?.let { return it }
            if (!visiting.add(bone.id)) {
                // Cyclic parent reference: treat as a root to avoid a stack overflow.
                val w = BoneWorld(bone.id, bone.restX, bone.restY, bone.restAngle + bone.rotation, bone.scale, bone.length)
                out[bone.id] = w
                return w
            }
            val parentWorld = bone.parentId?.let { byId[it] }?.let { resolve(it) }
            val worldAngle: Float
            val wx: Float
            val wy: Float
            val worldScale: Float
            if (parentWorld == null) {
                worldAngle = bone.restAngle + bone.rotation
                wx = bone.restX + bone.translateX
                wy = bone.restY + bone.translateY
                worldScale = bone.scale
            } else {
                worldAngle = parentWorld.worldAngle + bone.restAngle + bone.rotation
                val rad = Math.toRadians((parentWorld.worldAngle + bone.restAngle).toDouble())
                val parentLen = parentWorld.length * parentWorld.worldScale
                val ox = cos(rad).toFloat() * parentLen + bone.translateX
                val oy = sin(rad).toFloat() * parentLen + bone.translateY
                wx = parentWorld.x + ox
                wy = parentWorld.y + oy
                worldScale = parentWorld.worldScale * bone.scale
            }
            visiting.remove(bone.id)
            val world = BoneWorld(bone.id, wx, wy, worldAngle, worldScale, bone.length)
            out[bone.id] = world
            return world
        }

        bones.forEach { resolve(it) }
        return out
    }

    /**
     * Two-bone analytic IK (law of cosines). Returns the resulting angles for the
     * root and joint bones so limbs can be dragged by their tip.
     */
    fun solveTwoBoneIk(
        rootAngleDeg: Float,
        upperLength: Float,
        lowerLength: Float,
        targetX: Float,
        targetY: Float,
        rootX: Float,
        rootY: Float,
        bendPositive: Boolean = true,
    ): Pair<Float, Float>? {
        val dx = targetX - rootX
        val dy = targetY - rootY
        val dist = sqrt(dx * dx + dy * dy)
        val maxReach = upperLength + lowerLength
        val minReach = kotlin.math.abs(upperLength - lowerLength)
        if (dist > maxReach || dist < minReach || dist < 0.0001f) return null

        val targetAngle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        val cosRoot = (upperLength * upperLength + dist * dist - lowerLength * lowerLength) / (2f * upperLength * dist)
        val rootOffset = Math.toDegrees(kotlin.math.acos(cosRoot.coerceIn(-1f, 1f).toDouble())).toFloat()
        val cosJoint = (upperLength * upperLength + lowerLength * lowerLength - dist * dist) / (2f * upperLength * lowerLength)
        val jointAngle = Math.toDegrees(kotlin.math.acos(cosJoint.coerceIn(-1f, 1f).toDouble())).toFloat()

        val sign = if (bendPositive) 1f else -1f
        val rootRotation = targetAngle - rootAngleDeg + sign * rootOffset
        val jointRotation = -(180f - jointAngle) * sign
        return rootRotation to jointRotation
    }

    /** Bone length that reaches a rest target, for auto-rigging from a drawn limb. */
    fun lengthBetween(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    fun restAngleBetween(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        Math.toDegrees(kotlin.math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()

    /** Key a pose from the current bone rotations. */
    fun capturePose(name: String, bones: List<Bone>, id: String = "pose_${System.currentTimeMillis()}"): Pose =
        Pose(id = id, name = name, boneRotations = bones.associate { it.id to it.rotation })

    /** Applies a stored pose to the bone list (bones missing from the pose are unchanged). */
    fun applyPose(bones: List<Bone>, pose: Pose, blend: Float = 1f): List<Bone> {
        val t = blend.coerceIn(0f, 1f)
        return bones.map { bone ->
            val target = pose.boneRotations[bone.id] ?: return@map bone
            bone.copy(rotation = bone.rotation + (target - bone.rotation) * t)
        }
    }

    /** Blends two poses, used for tweening between key poses. */
    fun blendPoses(a: Pose, b: Pose, t: Float, id: String, name: String): Pose {
        val ids = a.boneRotations.keys + b.boneRotations.keys
        val blended = ids.associateWith { id0 ->
            val av = a.boneRotations[id0] ?: 0f
            val bv = b.boneRotations[id0] ?: 0f
            av + (bv - av) * t.coerceIn(0f, 1f)
        }
        return Pose(id = id, name = name, boneRotations = blended)
    }

    /** Orders bones so parents come before children (useful for UI list rendering). */
    fun hierarchyOrder(bones: List<Bone>): List<Bone> {
        val result = ArrayList<Bone>(bones.size)
        val emitted = HashSet<String>()
        fun emit(bone: Bone) {
            if (!emitted.add(bone.id)) return
            result.add(bone)
            bones.filter { it.parentId == bone.id }.forEach { emit(it) }
        }
        bones.filter { it.parentId == null || bones.none { b -> b.id == it.parentId } }.forEach { emit(it) }
        bones.forEach { emit(it) }
        return result
    }

    /** Facial expression / mouth-shape lip-sync helper: picks the closest phoneme slot. */
    fun mouthShapeForPhoneme(phoneme: String): String = when (phoneme.uppercase()) {
        "A", "AI", "AH" -> "A"
        "E", "EH" -> "E"
        "I", "EE" -> "I"
        "O", "OH" -> "O"
        "U", "OO", "W" -> "U"
        "M", "B", "P" -> "M"
        "F", "V" -> "F"
        "L", "TH" -> "L"
        else -> "rest"
    }

    /** Clamps a bone rotation into a human-plausible range for IK sanity. */
    fun clampRotation(deg: Float, minDeg: Float = -180f, maxDeg: Float = 180f): Float = min(max(deg, minDeg), maxDeg)
}
