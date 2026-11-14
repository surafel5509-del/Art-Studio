package com.artforge.mobile.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.model.ProjectKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class DebugCompositorTest {
    @Test
    fun debug() {
        val app = ApplicationProvider.getApplicationContext<ArtForgeApplication>()
        val repo = app.projectRepository
        val store = app.frameStore
        kotlinx.coroutines.runBlocking {
            val doc = repo.createDocument(ProjectKind.Illustration, "dbg", 2000, 3000, 24, 1, false, 0xFFFFFFFF.toInt())
            println("DBG layers=${doc.layers.size} kind=${doc.layers[0].kind} visible=${doc.layers[0].visible} opacity=${doc.layers[0].opacity} frames=${doc.layers[0].frames.size} active=${doc.layers[0].activeFrameId}")
            repo.ensureFrames(doc)
            val layer = doc.layers[0]
            val fid = layer.activeFrameId!!
            println("DBG frameId=$fid cacheCount=${store.cachedFrameCount()} bytes=${store.cacheBytes()}")
            val bmp = store.load(doc.id, fid, 2000, 3000)
            println("DBG loaded ${bmp.width}x${bmp.height} recycled=${bmp.isRecycled} px=${bmp.getPixel(1000,1500)}")
            val target = android.graphics.Bitmap.createBitmap(2000, 3000, android.graphics.Bitmap.Config.ARGB_8888)
            val comp = com.artforge.mobile.engine.draw.Compositor(store)
            comp.composite(doc, target, 0, includeBackground = true)
            println("DBG composite centre=${target.getPixel(1000,1500)} corner=${target.getPixel(5,5)}")
            val loaded2 = store.load(doc.id, fid, 2000, 3000)
            println("DBG after composite px=${loaded2.getPixel(1000,1500)} same=${loaded2 === bmp}")
        }
    }
}
