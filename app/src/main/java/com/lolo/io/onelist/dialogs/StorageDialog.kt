package com.lolo.io.onelist.dialogs

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.codekidlabs.storagechooser.Content
import com.codekidlabs.storagechooser.StorageChooser
import com.lolo.io.onelist.App
import com.lolo.io.onelist.MainActivity
import com.lolo.io.onelist.R
import kotlinx.android.synthetic.main.dialog_list_path.view.*
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
        downloadStorageButton.setOnClickListener {
            onPathChosen("Download/OneList")
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
        if (Build.VERSION.SDK_INT >= 29) {
            activity.onPathChosenActivityResult = onPathChosen
            Log.d("OneList", "Debugv Before SimpleStorageHelper callback func def")
            activity.storageHelper.onFolderSelected = { _, root -> // could also use simpleStorageHelper.onStorageAccessGranted()
                Log.d("OneList", "Debugv Success Folder Pick! Now saving...")
                Log.d("OneList", "Debugv Folder Pick New Saving Approach")
                val fpath = root.getAbsolutePath(appContext)
                /* Works but commented because unnecessary for working app, was used for debugging
                val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
                preferences.edit().putString("defaultPathPref", uri).apply()
                 */
                Log.d("OneList", "Debugv Folder Pick New File Creation")
                activity.onPathChosenActivityResult(fpath) // tip from https://github.com/anggrayudi/MaterialPreference/blob/5cd9b8653c71fae0314fa2bbf7f71c4c8c8f4104/materialpreference/src/main/java/com/anggrayudi/materialpreference/FolderPreference.kt
                activity.onPathChosenActivityResult = { }
            }
            Log.d("OneList", "Debugv Get Storage Access permission")
            activity.storageHelper.openFolderPicker(  // We could also use simpleStorageHelper.requestStorageAccess()
                    initialPath = FileFullPath(activity, StorageId.PRIMARY, "OneList"), // SimpleStorage.externalStoragePath
                    //expectedStorageType = StorageType.EXTERNAL,
                    //expectedBasePath = "OneList"
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.onPathChosenActivityResult = onPathChosen
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }, REQUEST_CODE_OPEN_DOCUMENT_TREE)
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
        if (Build.VERSION.SDK_INT >= 29) {
            activity.storageHelper.onFileSelected = { _, files ->
                val file = files.first()
                Log.d("OneList", "Debugv file selected: ${file.fullName}")
                onPathChosen(file.getAbsolutePath(activity))
            }
            activity.storageHelper.openFilePicker(
                    allowMultiple = false,
                    initialPath = FileFullPath(activity, StorageId.PRIMARY, "Download/OneList"), // SimpleStorage.externalStoragePath
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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
                            if (it.endsWith(".1list.json.txt")) {
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