package com.artforge.mobile.ui.assets

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.data.asset.AssetCategory
import com.artforge.mobile.data.asset.AssetLibrary
import com.artforge.mobile.data.db.AssetEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AssetLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val library = (application as ArtForgeApplication).assetLibrary

    val assets: StateFlow<List<AssetEntity>> = library.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    private val _searchResults = MutableStateFlow<List<AssetEntity>>(emptyList())
    val searchResults: List<AssetEntity> get() = _searchResults.value

    fun importAsset(uri: Uri, category: AssetCategory, name: String) {
        viewModelScope.launch {
            library.import(uri, category, name)
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            _searchResults.value = if (query.isBlank()) emptyList() else library.search(query)
        }
    }

    fun generateBuiltInTextures() {
        viewModelScope.launch {
            val textures = AssetLibrary.generateBuiltInTextures()
            textures.forEach { (name, bitmap) ->
                library.saveBitmap(bitmap, AssetCategory.Texture, name, "generated,built-in")
                bitmap.recycle()
            }
            AssetLibrary.builtInGradients().forEach { (name, colors) ->
                val preview = AssetLibrary.gradientPreview(colors)
                library.saveBitmap(preview, AssetCategory.Gradient, name, "gradient")
                preview.recycle()
            }
        }
    }
}
