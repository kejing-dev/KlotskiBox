package io.embodit.klotskibox.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.domain.Shape
import io.embodit.klotskibox.ui.board.input.GazeTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The marks of the volume (arrows, glows, the gap strip, the counter plate) and the collider shapes. The wooden models
 * are GLBs (tools/build_models.py); only abstract marks are SDK meshes. Creating a resource waits on the renderer, so
 * everything is created concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var arrowPlane: MeshResource; private set
    lateinit var arrowMat: Material; private set
    lateinit var arrowGrey: Material; private set
    lateinit var haloPlanes: Map<Shape, MeshResource>; private set
    lateinit var haloMat: Material; private set
    lateinit var haloCoach: Material; private set
    lateinit var haloHover: Material; private set
    lateinit var stripPlane: MeshResource; private set
    lateinit var stripWeak: Material; private set
    lateinit var stripStrong: Material; private set
    lateinit var platePlane: MeshResource; private set

    lateinit var cellShape: ShapeResource; private set
    lateinit var tileShapes: Map<Shape, ShapeResource>; private set
    lateinit var boxShape: ShapeResource; private set
    lateinit var markerParts: List<GazeTargets.Part>; private set
    lateinit var marker: Material; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        /**
         * Gaze-target markers (board-input template, "标记尺寸" 2026-11-06): <= 5 mm, the floor's colour half a shade darker,
         * 1.2 mm proud; the click area stays the whole 7 cm cell.
         */
        val MARKER_STYLE = GazeTargets.Style(fraction = 0.005f / Layout.PITCH, thick = 0.0012f, corner = 0.001f)
        const val HALO_MARGIN = 0.008f

        private fun unlit(color: Color, opacity: Float = 1f, tex: TextureResource? = null): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f || tex != null) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                tex?.let { setBaseColorTexture(it) }
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T = r.keep(this)
            val io = Dispatchers.IO
            val arrowTex = async(io) { TextureResource.create(BoardTextures.arrow()).keep() }
            val arrowGreyTex = async(io) { TextureResource.create(BoardTextures.arrow(fill = android.graphics.Color.rgb(0x9A, 0x92, 0x88))).keep() }
            val stripTex = async(io) { TextureResource.create(BoardTextures.strip()).keep() }
            val haloTex = Shape.entries.associateWith { s ->
                val sz = Layout.size(s)
                async(io) { TextureResource.create(BoardTextures.halo(sz.x, sz.z, HALO_MARGIN)).keep() }
            }
            val haloMeshes = Shape.entries.associateWith { s ->
                val sz = Layout.size(s)
                async(io) { MeshResource.createPlane(sz.x + 2 * HALO_MARGIN, sz.z + 2 * HALO_MARGIN, 0f).keep() }
            }
            val shapes = Shape.entries.associateWith { s ->
                val sz = Layout.size(s)
                async(io) { ShapeResource.createBox(Vector3(sz.x, sz.y, sz.z)).keep() }
            }
            listOf(
                async(io) { r.arrowPlane = MeshResource.createPlane(Layout.ARROW, Layout.ARROW, 0f).keep() },
                async(io) { r.stripPlane = MeshResource.createPlane(Layout.GAP_W - 0.004f, 0.022f, 0f).keep() },
                async(io) { r.platePlane = MeshResource.createPlane(Layout.PLATE_W, Layout.PLATE_H, 0f).keep() },
                async(io) { r.cellShape = ShapeResource.createBox(Vector3(Layout.PITCH, 0.004f, Layout.PITCH)).keep() },
                async(io) { r.boxShape = ShapeResource.createBox(Vector3(Layout.OUTER_W, Layout.FLOOR_T + Layout.WALL_H, Layout.OUTER_D)).keep() },
                async(io) { r.markerParts = GazeTargets.markerMeshes(Layout.PITCH, MARKER_STYLE).also { ps -> ps.map { it.mesh }.distinct().forEach { it.keep() } } },
                async(io) {
                    // the floor walnut (#3E2A1C) half a shade darker, opaque PBR: the system hover is drawn on it
                    r.marker = PhysicallyBasedMaterial.create().apply {
                        setBaseColor(Color(0xFF33231A).c4(1f)); setRoughness(0.6f); setMetallic(0f)
                    }.keep()
                },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r.arrowMat = unlit(Color.White, tex = arrowTex.await()).keep()
            r.arrowGrey = unlit(Color.White, tex = arrowGreyTex.await()).keep()
            r.stripWeak = unlit(Color.White, opacity = 0.25f, tex = stripTex.await()).keep()
            r.stripStrong = unlit(Color.White, opacity = 1f, tex = stripTex.await()).keep()
            val halos = haloTex.mapValues { it.value.await() }
            r.haloMat = unlit(Color.White, opacity = 0.95f, tex = halos[Shape.BIG]).keep()
            r.haloPlanes = haloMeshes.mapValues { it.value.await() }
            r.tileShapes = shapes.mapValues { it.value.await() }
            // one material per shape (the texture is sized to the tile); coach = softer, hover = faint
            r.haloByShape = halos.mapValues { (_, t) -> unlit(Color.White, opacity = 0.95f, tex = t).keep() }
            r.coachByShape = halos.mapValues { (_, t) -> unlit(Color.White, opacity = 0.55f, tex = t).keep() }
            r.hoverByShape = halos.mapValues { (_, t) -> unlit(Color(0xFFFFFFFF), opacity = 0.35f, tex = t).keep() }
            r.haloCoach = r.coachByShape[Shape.BIG]!!
            r.haloHover = r.hoverByShape[Shape.BIG]!!
            r
        }
    }

    lateinit var haloByShape: Map<Shape, Material>; private set
    lateinit var coachByShape: Map<Shape, Material>; private set
    lateinit var hoverByShape: Map<Shape, Material>; private set
}
