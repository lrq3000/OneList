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
import com.lolo.io.onelist.util.toUri
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import com.anggrayudi.storage.media.FileDescription
import com.anggrayudi.storage.media.MediaStoreCompat
import com.lolo.io.onelist.updates.appContext
import com.lolo.io.onelist.util.getNewPath

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
            val list = fileUri?.let { uri ->
                var ins: InputStream? = null
                try {
                    ins = App.instance.contentResolver.openInputStream(uri)
                    gson.fromJson(ins!!.reader(), ItemList::class.java)
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
                    if (defaultPath == "Download/OneList") {
                        Log.d("MyApp", "Debugv getList from Download/OneList: " + path)
                        if (path != null) {
                            openDownloadFileFromFilename(appContext, path, mode = CreateMode.REUSE, writeAccess = false)?.openInputStream(appContext)
                        } else {
                            null
                        }
                    } else {
                        val fileUri = path.toUri
                        if (fileUri != null) {
                            App.instance.contentResolver.openInputStream(fileUri)
                        } else {
                            null
                        }
                    }
            val list = ins?.let { ins ->
                try {
                    gson.fromJson(ins!!.reader(), ItemList::class.java)
                } catch (e: Exception) {
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_opening_filepath), Toast.LENGTH_LONG).show() }
                    null
                } finally {
                    ins?.close()
                }
            } ?: path.takeIf { it?.isNotBlank() == true }?.let {
                try {
                    val json = File(path).readText()
                    gson.fromJson(json, ItemList::class.java)
                } catch (e: Exception) {
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_opening_filepath, path), Toast.LENGTH_LONG).show() }
                    null
                }
            } ?: gson.fromJson(sp.getString(listId.toString(), ""), ItemList::class.java)

            list.apply {
                this.path = path ?: ""
            }
        }
    }

    fun saveListAsync(list: ItemList) {
        Log.d("MyApp", "Debugv saveListAsync")
        GlobalScope.launch {
            saveList(list)
        }
    }

    fun saveList(list: ItemList) {
        val sp = app.getPreferences(Context.MODE_PRIVATE)
        Log.d("MyApp", "Debugv saveList defaultPath: " + defaultPath)
        val editor = sp.edit()
        val gson = Gson()
        val json = gson.toJson(list)
        Log.d("MyApp", "Debugv saveList")
        try {
            val out =
            if (defaultPath == "Download/OneList") {
                Log.d("MyApp", "Debugv saveList to Download/OneList")
                openDownloadFileFromFilename(appContext, list.getNewPath(list.title), mode=CreateMode.REPLACE, writeAccess=true)!!.openOutputStream(appContext)
            } else {
                // The following is not working
                val path = defaultPath + list.getNewPath(list.title)
                Log.d("MyApp", "Debugv saveList try: " + path.toString())
                //val fileUri = list.path.toUri
                val fileUri = path.toUri
                //DocumentFile.fromSingleUri(appContext, fileUri)!!.openOutputStream(appContext)
                //DocumentFileCompat.fromUri(appContext, fileUri)!!.openOutputStream(appContext)
                App.instance.contentResolver.openOutputStream(fileUri!!)
            }
            Log.d("MyApp", "Debugv saveList just before let block")
            out?.let { out ->
                try {
                    Log.d("MyApp", "Debugv saveList try to write")
                    out!!.write(json.toByteArray(Charsets.UTF_8)) // NPE is catched below
                    Log.d("MyApp", "Debugv saveList write successful!")
                } catch (e: Exception) {
                    app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_saving_to_path), Toast.LENGTH_LONG).show() }
                    Log.d("MyApp", "Debugv saveList unable to write: " + e.stackTraceToString())
                } finally {
                    out?.close()
                }
            } ?: if (list.path.isNotBlank()) {
                File(list.path).writeText(json)
            }
        } catch (e: Exception) {
            app.runOnUiThread { Toast.makeText(App.instance, app.getString(R.string.error_saving_to_path, list.path), Toast.LENGTH_LONG).show() }
        }

        // save in prefs anyway
        editor.putString(list.stableId.toString(), json)
        editor.apply()
    }

    fun removeListFile(list: ItemList) {
        GlobalScope.launch {
            if (list.path.isNotBlank()) {
                try {
                    File(list.path).delete()
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
        var lists_concat = ""
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

    fun openDownloadFileFromFilename(context: Context, filepath: String, mode: CreateMode, writeAccess: Boolean): FileWrapper? {
        // simply use like this: openDownloadFileFromFilename(appContext, "filepathtest.txt")!!.openOutputStream(appContext)!!.write("OutTest".toByteArray(Charsets.UTF_8))
        val fileDesc = FileDescription(filepath, "OneList", "text/*")
        //Log.d("MyApp", "Debugv Open filetest in downloads")
        //val filetest = DocumentFileCompat.createDownloadWithMediaStoreFallback(appContext, fileDesc)
        return reopenDownloadFile(appContext, fileDesc, mode, writeAccess)
    }

    fun reopenDownloadFile(context: Context, file: FileDescription, mode: CreateMode, writeAccess: Boolean): FileWrapper? {
        // use CreateMode.REUSE to append or create a new file if none exist, or CreateMode.REPLACE if we want to rewrite the whole file from 0
        // Note that strangely, only MediaStoreCompat works on Android 10+, and for it to create a document instead of a binary object, it needs to recognize the file extension, such as .json or .txt
        // If the extension is just .1list, then the file is saved as a binary and the mode REPLACE and REUSE won't work for some reason, a new file will be created each time. If the extension is .json or .txt, then the specified mode is respected.
        val publicFolder = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, requiresWriteAccess = writeAccess)
        return if (publicFolder == null && Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            MediaStoreCompat.createDownload(context, file, mode= mode)?.let { FileWrapper.Media(it) }
        } else {
            publicFolder?.makeFile(context, file.name, file.mimeType, mode= mode)?.let { FileWrapper.Document(it) }
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
