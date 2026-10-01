package com.espitman.sdm.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CategoryFolderStore internal constructor(private val prefs:SharedPreferences) {
    private val mutable=MutableStateFlow(read())
    val settings:StateFlow<CategoryFolderSettings> = mutable.asStateFlow()
    private val unavailableMutable=MutableStateFlow<Set<FileCategory>>(emptySet())
    val unavailable:StateFlow<Set<FileCategory>> = unavailableMutable.asStateFlow()
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->mutable.value=read()}
    init {prefs.registerOnSharedPreferenceChangeListener(listener)}
    fun setEnabled(enabled:Boolean) {check(prefs.edit().putBoolean("enabled",enabled).commit());mutable.value=read()}
    fun replace(value:CategoryFolderSettings) {
        val editor=prefs.edit().clear().putBoolean("enabled",value.enabled)
        value.rules.forEach { (category,rule)-> editor.putString("${category.name}.uri",rule.treeUri).putString("${category.name}.label",rule.label) }
        check(editor.commit());mutable.value=read();unavailableMutable.value=emptySet()
    }
    fun save(category:FileCategory,rule:CategoryFolderRule?) {
        val editor=prefs.edit()
        if(rule==null) editor.remove("${category.name}.uri").remove("${category.name}.label")
        else editor.putString("${category.name}.uri",rule.treeUri).putString("${category.name}.label",rule.label)
        check(editor.commit());mutable.value=read();unavailableMutable.value=unavailableMutable.value-category
    }
    @Synchronized fun markAvailable(category:FileCategory) {unavailableMutable.value=unavailableMutable.value-category}
    @Synchronized fun markUnavailable(category:FileCategory) {unavailableMutable.value=unavailableMutable.value+category}
    private fun read()=CategoryFolderSettings(prefs.getBoolean("enabled",false),FileCategory.entries.mapNotNull { category->
        val uri=prefs.getString("${category.name}.uri",null) ?: return@mapNotNull null
        category to CategoryFolderRule(uri,prefs.getString("${category.name}.label",null) ?: category.label)
    }.toMap())
    companion object {
        @Volatile private var instance:CategoryFolderStore?=null
        fun get(context:Context):CategoryFolderStore=instance ?: synchronized(this) {
            instance ?: CategoryFolderStore(context.applicationContext.getSharedPreferences("sdm_category_folders",Context.MODE_PRIVATE)).also {instance=it}
        }
    }
}
