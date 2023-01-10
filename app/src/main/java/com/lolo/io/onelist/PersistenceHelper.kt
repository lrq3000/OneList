package com.lolo.io.onelist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.FileWrapper
import com.anggrayudi.storage.file.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lolo.io.onelist.model.ItemList
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import com.anggrayudi.storage.media.FileDescription
import com.anggrayudi.storage.media.MediaStoreCompat
import com.lolo.io.onelist.updates.appContext
import com.lolo.io.onelist.util.*

class PersistenceHelper(private val app: Activity) {

    val versionPref: String = "version"
    private val selectedListPref = "selectedList"
    private val listIdsPref = "listsIds"
    private val defaultPathPref = "defaultPath"
    val themePref: String = "theme"

    private var listsIds: Map<Long, String> = linkedMapOf()

    var defaultPath: String
        get() {
            val sp = app.getPreferences(Context.MODE_PRIVATE)
            return sp.getString(defaultPathPref, "") ?: ""
        }
        set(value) {
            val sp = app.getPreferences(Context.MODE_PRIVATE)
            val editor = sp.edit()
            editor.putString(defaultPathPref, value)
            editor.apply()
        }

    var version: String
        get() {
            val sp = app.getPreferences(Context.MODE_PRIVATE)
            return sp.getString(versionPref, "0.0.0") ?: "0.0.0"
        }
        set(value) {
            val sp = app.getPreferences(Context.MODE_PRIVATE)
            val editor = sp.edit()
            editor.putString(versionPref, value)
            editor.apply()
        }

    fun getTheme(context: Context): String {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            return sp.getString(themePref, "auto") ?: "auto"
        }

    fun getAllLists(): List<ItemList> {
        return runBlocking {
            listsIds = getListIdsTable()
            try {
                val ret = listsIds.map { getListAsync(it.key).await() }
                ret
            } catch (e: Exception) {
                listOf<ItemList>()
            }
        }
    }


    fun refreshAndFetchNewLists(lists: MutableList<ItemList>) {
        runBlocking {
            val newIds = getListIdsTable()
            newIds.forEach { fetchedId ->
                if (!listsIds.keys.contains(fetchedId.key)) {
                    lists.add(getListAsync(fetchedId.key).await())
                }
            }
            listsIds = newIds
            refreshAllLists(lists)
        }
    }

    fun refreshAllLists(lists: List<ItemList>) {
        runBlocking {
            lists.forEach {
                it.items.clear()
                it.items.addAll(getListAsync(it.stableId).await().items)
            }
        }
    }

    fun updateListIdsTableAsync(lists: List<ItemList>) {
        GlobalScope.launch {
            updateListIdsTable(lists)
        }
    }

    fun updateListIdsTable(lists: List<ItemList>) {
        listsIds = lists.map { it.stableId to it.path }.toMap()
        val sp = app.getPreferences(Context.MODE_PRIVATE)
        val editor = sp.edit()
        val gson = Gson()
        val json = gson.toJson(listsIds)
        editor.putString(listIdsPref, gson.toJson(json))
        editor.apply()
    }

    private fun getListIdsTable(): Map<Long, String> {
        val sp = app.getPreferences(Context.MODE_PRIVATE)
        val gson = Gson()
        val json = sp.getString(listIdsPref, null)?.replace("\\", "")?.removeSurrounding("\"")
        return if (json != null) {
            gson.fromJson(json, object : TypeToken<Map<Long, String>>() {
            }.type)
        } else mapOf()
    }

    fun createListFromUri(uri: Uri): ItemList {
        try {
            val gson = Gson()
            val content = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            val list = gson.fromJson(content, ItemList::class.java)
            list.path = ""
            require(!listsIds.containsKey(list.stableId)) { app.getString(R.string.list_already_in_your_lists) }
            return list
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IOException(app.getString(R.string.error_opening_file))
        }
    }

    fun importList(filePath: String): ItemList {
        try {
            val gson = Gson()
            val fileUri = filePath.toUri
            Log.d("OneList", "Debugv importList: path: $filePath")
            val list = fileUri?.let { uri ->
                var ins: InputStream? = null
                try {
                    Log.d("OneList", "Debugv importList: uri: " + uri.toString())
                    ins =
                        if (filePath.contains("Download/OneList")) {
                                Log.d("OneList", "Debugv importList from Download/OneList: path: " + filePath + " fileName: " + filePath.substringAfterLast("Download/OneList/"))
                                // TODO: the following line only works when trying to import a list file that was created by the app with current permissions. Once the app's storage is erased, then permissions are reset, and previously generated files aren't considered part of the app permissions anymore, so we cannot access them anymore and that's why we get a null when we try to .openInputStream(), simply because we don't have the permissions.
                                // The solution is to rewrite the WHOLE app to only use scoped URIs, and never strings. Using strings only work when creating a list in the Download folder, but not when accessing a file previously generated or generated by another app (eg, file syncing apps).
                                openDownloadFileFromFilename(appContext, filePath.substringAfterLast("Download/OneList/"), mode=CreateMode.REUSE, writeAccess=true)?.openInputStream(appContext)
                        } else {
                            if (Build.VERSION.SDK_INT >= 29) {
                                val file = DocumentFileCompat.fromFullPath(appContext, uri.toString()!!, requiresWriteAccess=false)
                                Log.d("OneList", "Debugv Try to open inputstream")
                                file!!.openInputStream(appContext)
                            } else {
                                App.instance.contentResolver.openInputStream(uri)
                            }
                        }
                    Log.d("OneList", "Debugv importList: openInputStream successful!")
                    Log.d("OneList", "Debugv importList: openInputStream file handle: " + ins.toString())
                    val ret = gson.fromJson(ins!!.reader(), ItemList::class.java)
                    Log.d("OneList", "Debugv importList: reader successful! Returning.")
                    return ret
                } catch (e: Exception) {
                    throw Exception()
                } finally {
                    ins?.close()
                }
            } ?: filePath.takeIf { it.isNotBlank() }?.let {
                val json = File(it).readText()
                gson.fromJson(json, ItemList::class.java)
            } ?: throw Exception()

            require(!listsIds.containsKey(list.stableId)) { app.getString(R.string.list_already_in_your_lists) }
            return list
        } catch (e: IOException) {
            throw IOException(app.getString(R.string.error_opening_file))
        }
    }

    private fun getListAsync(listId: Long): Deferred<ItemList> {
        return GlobalScope.async {
            val gson = Gson()
            val sp = app.getPreferences(Context.MODE_PRIVATE)
            val path = listsIds[listId]
            val ins =
                    if (path?.contains("Download/OneList") == true) {
                        Log.d("OneList", "Debugv getList from Download/OneList: path: " + path + " fileName: " + path.substringAfterLast("/"))
                        openDownloadFileFromFilename(appContext, path.substringAfterLast("Download/OneList/"), mode=CreateMode.REUSE, writeAccess=true)?.openInputStream(appContext)
                    } else {
                        if (Build.VERSION.SDK_INT >= 29) {
                            val file = DocumentFileCompat.fromFullPath(appContext, path!!, requiresWriteAccess=false)
                            Log.d("OneList", "Debugv Try to open inputstream")
                            file?.openInputStream(appContext)
                        } else {
                            val fileUri = path?.toUri
                            if (fileUri != null) {
                                App.instance.contentResolver.openInputStream(fileUri)
                            } else {
                                null
                            }
                        }
                    }
            val list = ins?.let { ins ->
                try {
                    Log.d("OneList", "Debugv getList try to read file")
                    gson.fromJson(ins!!.reader(), ItemList::class.java)
                } catch (e: Exception) {
                    Log.d("OneList", "Debugv getList error: " + e.stackTraceToString())
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_opening_filepath), Toast.LENGTH_LONG).show() }
                    val grantedPaths: Map<String, Set<String>> = DocumentFileCompat.getAccessibleAbsolutePaths(appContext)
                    Log.d("OneList", "Debugv getList grantedPaths: " + grantedPaths.toString())
                    null
                } finally {
                    ins?.close()
                }
            } ?: path.takeIf { it?.isNotBlank() == true }?.let {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        val json = File(path).readText()
                        gson.fromJson(json, ItemList::class.java)
                    } catch (e: Exception) {
                        Log.d("OneList", "Debugv getList error in path.takeIf: " + e.stackTraceToString())
                        app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_opening_filepath, path), Toast.LENGTH_LONG).show() }
                        null
                    }
                } else {
                    null
                }
            } ?: gson.fromJson(sp.getString(listId.toString(), ""), ItemList::class.java)

            list.apply {
                this.path = path ?: ""
            }
        }
    }

    fun saveListAsync(list: ItemList) {
        Log.d("OneList", "Debugv saveListAsync")
        GlobalScope.launch {
            saveList(list)
        }
    }

    fun saveList(list: ItemList) {
        Log.d("OneList", "Debugv saveList defaultPath: " + defaultPath)
        val gson = Gson()
        val json = gson.toJson(list)
        Log.d("OneList", "Debugv saveList")
        try {
            val path = list.path
            Log.d("OneList", "Debugv saveList to list path: " + list.path)
            val out =
            if (path?.contains("Download/OneList") == true) {
                Log.d("OneList", "Debugv saveList to Download/OneList")
                assert(list.fileName == path.substringAfterLast("Download/OneList/"))
                openDownloadFileFromFilename(appContext, list.fileName, mode=CreateMode.REPLACE, writeAccess=true)!!.openOutputStream(appContext)
            } else {
                if (Build.VERSION.SDK_INT >= 29) {
                    //val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
                    //val path = preferences.getString("rootpath", null)
                    val file = DocumentFileCompat.fromFullPath(appContext, path!!, requiresWriteAccess=true)
                    Log.d("OneList", "Debugv Try to open outputstream")
                    file?.openOutputStream(appContext, append=false)
                } else {
                    App.instance.contentResolver.openOutputStream(path.toUri!!)
                }
            }
            Log.d("OneList", "Debugv saveList just before let block")
            out?.let { out ->
                try {
                    Log.d("OneList", "Debugv saveList try to write")
                    out!!.write(json.toByteArray(Charsets.UTF_8)) // NPE is catched below
                    Log.d("OneList", "Debugv saveList write successful!")
                } catch (e: Exception) {
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_saving_to_path), Toast.LENGTH_LONG).show() }
                    Log.d("OneList", "Debugv saveList unable to write: " + e.stackTraceToString())
                } finally {
                    out?.close()
                }
            } ?: if (list.path.isNotBlank()) {
                File(list.path).writeText(json)
            }
        } catch (e: Exception) {
            app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_saving_to_path, list.path), Toast.LENGTH_LONG).show() }
        }

        Log.d("OneList", "Debugv saveList json: " + json)

        // save in prefs anyway, so we fallback on app private storage copy of the list if the other storage fails or if none is selected
        val sp = app.getPreferences(Context.MODE_PRIVATE)
        val editor = sp.edit()
        editor.putString(list.stableId.toString(), json)
        editor.apply()
    }

    fun removeListFile(list: ItemList) {
        // Delete list file from disk
        GlobalScope.launch {
            if (list.path.isNotBlank()) {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val file = DocumentFileCompat.fromFullPath(appContext, list.path!!, requiresWriteAccess=true)
                        file!!.delete()
                    } else {
                        File(list.path).delete()
                    }
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.file_deleted), Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_deleting_list_file), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun shareList(list: ItemList) {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, list.toString()) // toString() is overloaded to output the list's title, content and an ad for the software, except if toStringNoAd() is used
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        app.startActivity(shareIntent)
    }

    fun shareAllLists() {
        // Fetch list of all lists
        var lists = getAllLists()
        // Concat content of every lists
        var lists_concat = "# ALL LISTS\n-----\n\n"
        for (l in lists) {
            // toString() is overloaded to output the list's title, content and an ad for the software, except if toStringNoAd() is used
            lists_concat += l.toStringNoAd() + "\n\n----\n\n"
        }
        // Append the ad once at the very end
        lists_concat += lists[0].toStringOnlyAd()
        // Share dialog
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, lists_concat)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        app.startActivity(shareIntent)
    }

    var selectedListIndex: Int
        get() {
            val sp = app.getPreferences(Context.MODE_PRIVATE)
            return sp.getInt(selectedListPref, 0)
        }
        set(value) {
            GlobalScope.launch {
                val sp = app.getPreferences(Context.MODE_PRIVATE)
                val editor = sp.edit()
                editor.putInt(selectedListPref, value)
                editor.apply()
            }
        }

    // Only to handle architecture updates between versions. do not use
    val compat = Compat()

    inner class Compat {

        private val firstLaunchPrefCompat = "firstLaunch"
        private val listsPrefsCompat = "lists"

        val allListsCompat: List<ItemList>
            get() {
                val sp = app.getPreferences(Context.MODE_PRIVATE)
                val gson = Gson()
                val json = sp.getString(listsPrefsCompat, null)
                var lists: List<ItemList> = ArrayList()
                if (json != null) {
                    lists = gson.fromJson(json, object : TypeToken<List<ItemList>>() {
                    }.type)
                }
                return lists
            }

        var firstLaunchCompat: Boolean
            get() {
                val sp = app.getPreferences(Context.MODE_PRIVATE)
                return sp.getBoolean(firstLaunchPrefCompat, true)
            }
            set(value) {
                val sp = app.getPreferences(Context.MODE_PRIVATE)
                val editor = sp.edit()
                editor.putBoolean(firstLaunchPrefCompat, value)
                editor.apply()
            }
    }
}
