package com.lolo.io.onelist.dialogs

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.codekidlabs.storagechooser.Content
import com.codekidlabs.storagechooser.StorageChooser
import com.lolo.io.onelist.App
import com.lolo.io.onelist.MainActivity
import com.lolo.io.onelist.R
import kotlinx.android.synthetic.main.dialog_list_path.view.*
import java.io.File
import java.net.URI
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.file.*
import com.lolo.io.onelist.updates.appContext
import com.lolo.io.onelist.util.*


@SuppressLint("InflateParams")
fun defaultPathDialog(activity: MainActivity, onPathChosen: (String) -> Unit) {
    val view = LayoutInflater.from(activity).inflate(R.layout.dialog_list_path, null)
    view.listPathTitle.text = activity.getString(R.string.default_storage_folder)
    displayDialog(view, activity, onPathChosen)
}

@SuppressLint("InflateParams")
fun storagePathDialog(activity: MainActivity, onPathChosen: (String) -> Unit) {
    val view = LayoutInflater.from(activity).inflate(R.layout.dialog_list_path, null)
    displayDialog(view, activity, onPathChosen)
}

fun displayDialog(view: View, activity: MainActivity, onPathChosen: (String) -> Unit) {

    val dialog = AlertDialog.Builder(activity).run {
        setView(view)
        create()
    }.apply {
        setCanceledOnTouchOutside(true)
        show()
    }

    view.apply {
        appPrivateStorageButton.setOnClickListener {
            onPathChosen("")
            dialog.dismiss()
        }
        chooseFolderButton.setOnClickListener {
            selectDirectory(activity, onPathChosen)
            dialog.dismiss()
        }

        helpChangePath.setOnClickListener {
            AlertDialog.Builder(activity)
                    .setMessage(R.string.changeFolderHelp)
                    .setPositiveButton(activity.getString(R.string.ok_with_tick), null)
                    .create().apply {
                        setCanceledOnTouchOutside(true)
                    }.show()
        }
        cancelChangePath.setOnClickListener { dialog.dismiss() }

    }
}

fun selectDirectory(activity: MainActivity, onPathChosen: (String) -> Any?) {
    withStoragePermission(activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.onPathChosenActivityResult = onPathChosen
            //activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            //    addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            //}, REQUEST_CODE_OPEN_DOCUMENT_TREE)
            Log.d("MyApp", "Debugv Before SimpleStorageHelper callback func def")
            activity.storageHelper.onStorageAccessGranted = { requestCode, root ->
                activity.onPathChosenActivityResult(root.getAbsolutePath(activity))
                //activity.onPathChosenActivityResult = { }
            }
            activity.storageHelper.onFolderSelected = { requestCode, folder ->
                Log.d("MyApp", "Debugv Success Folder Pick! Now saving...")
                val uri = folder.getAbsolutePath(activity)
                activity.onPathChosenActivityResult(uri) // tip from https://github.com/anggrayudi/MaterialPreference/blob/5cd9b8653c71fae0314fa2bbf7f71c4c8c8f4104/materialpreference/src/main/java/com/anggrayudi/materialpreference/FolderPreference.kt
                //activity.onPathChosenActivityResult = { }
                Log.d("MyApp", "Debugv Success Folder Pick Save!")
                Log.d("MyApp", "Debugv Try to create a file testfilename.txt")
                val newFile = folder.makeFile(appContext, "testfilename.txt", "text/*")
                Log.d("MyApp", "Debugv Try to write in the file testfilename.txt")
                val out = newFile!!.openOutputStream(appContext)
                try {
                    Log.d("MyApp", "Debugv Write test file...")
                    out!!.write("Lalala".toByteArray(Charsets.UTF_8))
                    Log.d("MyApp", "Debugv Write test file successful!")
                } catch (e: Exception) {
                    Log.d("MyApp", "Debugv unable to write test file: " + e.stackTraceToString())
                } finally {
                    out?.close()
                }
                //val path = activity.persistence.defaultPath + "testfilename.txt"
                val path = "$uri/testfilename.txt"
                val out2 = DocumentFileCompat.fromFullPath(appContext, path)!!.openOutputStream(appContext)
                try {
                    Log.d("MyApp", "Debugv Write test file again on path: $path")
                    out2!!.write("Second output!".toByteArray(Charsets.UTF_8)) // NPE is catched below
                    Log.d("MyApp", "Debugv Write test file again successful!")
                } catch (e: Exception) {
                    Log.d("MyApp", "Debugv unable to write test file again: " + e.stackTraceToString())
                } finally {
                    out?.close()
                }
            }
            //Log.d("MyApp", "Debugv Get Storage Access permission")
            /*
            activity.storageHelper.requestStorageAccess(
                    initialPath = FileFullPath(activity, StorageId.PRIMARY, "OneList"), // SimpleStorage.externalStoragePath
                    expectedStorageType = StorageType.EXTERNAL,
                    expectedBasePath = "OneList"
            )
            */
            Log.d("MyApp", "Debugv Before Folder Picker")
            activity.storageHelper.openFolderPicker(
                    initialPath = FileFullPath(activity, StorageId.PRIMARY, "OneList"), // SimpleStorage.externalStoragePath
            )
            Log.d("MyApp", "Debugv After Folder Picker!")
            //Log.d("MyApp", "Debugv Create a file using another function, with distinct permissions")
            //activity.storageHelper.createFile("text/plain", "Test create file")
        } else {
            @Suppress("DEPRECATION")
            StorageChooser.Builder()
                    .withActivity(activity)
                    .withContent(storageChooserLocales)
                    .withFragmentManager(activity.fragmentManager) // activity.fragmentManager deprecated, but lib StorageChooser hasn't fully migrated to androidx yet.
                    .withMemoryBar(true)
                    .allowCustomPath(true)
                    .allowAddFolder(true)
                    .setType(StorageChooser.DIRECTORY_CHOOSER)
                    .build()
                    .apply {
                        show()
                        setOnSelectListener {
                            onPathChosen(it)
                        }
                    }
        }
    }
}

fun selectFile(activity: MainActivity, onPathChosen: (String) -> Any?) {
    withStoragePermission(activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.onPathChosenActivityResult = onPathChosen
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*" }, REQUEST_CODE_OPEN_DOCUMENT)
        } else {
            @Suppress("DEPRECATION")
            StorageChooser.Builder()
                    .withActivity(activity)
                    .withFragmentManager(activity.fragmentManager) // activity.fragmentManager deprecated, but lib StorageChooser hasn't fully migrated to androidx yet.
                    .withMemoryBar(true)
                    .allowCustomPath(true)
                    .setType(StorageChooser.FILE_PICKER)
                    .build()
                    .apply {
                        show()
                        setOnSelectListener {
                            if (it.endsWith(".1list")) {
                                onPathChosen(it)
                            } else {
                                Toast.makeText(activity, activity.getString(R.string.not_a_1list_file), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
        }
    }
}

val storageChooserLocales = Content().apply {
    selectLabel = App.instance.getString(R.string.storage_chooser_select_label)
    createLabel = App.instance.getString(R.string.storage_chooser_create_label)
    newFolderLabel = App.instance.getString(R.string.storage_chooser_new_folder_label)
    cancelLabel = App.instance.getString(R.string.storage_chooser_cancel_label)
    overviewHeading = App.instance.getString(R.string.storage_chooser_overview_heading)
    internalStorageText = App.instance.getString(R.string.storage_chooser_internal_storage_text)
    freeSpaceText = App.instance.getString(R.string.storage_chooser_free_space_text)
    folderCreatedToastText = App.instance.getString(R.string.storage_chooser_folder_created_toast_text)
    folderErrorToastText = App.instance.getString(R.string.storage_chooser_folder_error_toast_text)
    textfieldHintText = App.instance.getString(R.string.storage_chooser_text_field_hint_text)
    textfieldErrorText = App.instance.getString(R.string.storage_chooser_text_field_error_text)
}