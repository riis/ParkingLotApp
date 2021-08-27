package com.dji.droneparking.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dji.droneparking.R
import com.dji.droneparking.core.PhotoStitcherActivity
import com.dji.droneparking.databinding.RecyclerViewDialogBinding
import java.io.File


class RecyclerViewDialog(val filesList: Array<File>?): DialogFragment() {

    private lateinit var fileRecyclerView: RecyclerView
    private var mFileAdapter: FileListAdapter? = null

    private lateinit var mDialog: Dialog
    private var _binding: RecyclerViewDialogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = RecyclerViewDialogBinding.inflate(LayoutInflater.from(context))

        mDialog = context?.let { Dialog(it) }!!
        mDialog.setContentView(binding.root)

        mDialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        fileRecyclerView = binding.fileRecyclerView as RecyclerView
        fileRecyclerView.layoutManager = LinearLayoutManager(context)

        mFileAdapter = FileListAdapter()
        fileRecyclerView.adapter = mFileAdapter

        return mDialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

    }

    inner class ItemHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView), View.OnClickListener {

        init {
            itemView.setOnClickListener(this)
        }
        var fileListItemTextView: TextView = itemView.findViewById(R.id.file_list_item_text_view)
        var fileListItemLinearLayout: LinearLayout = itemView.findViewById(R.id.file_list_item_linear_layout)

        override fun onClick(itemView: View) {
            fileListItemLinearLayout.setBackgroundColor(resources.getColor(R.color.gray_light))
            val file = filesList?.get(itemView.tag as Int)
            if (file != null) {
                returnSelectedFile(file)
                Log.d("BANANAPIE", "watch this too"+file.toString())
            }
        }
        }


    private inner class FileListAdapter() : RecyclerView.Adapter<ItemHolder>() {

        override fun getItemCount(): Int {
            return filesList?.size ?: 0
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_item_recycler_view_dialog, parent, false)
            return ItemHolder(view)
        }

        override fun onBindViewHolder(mItemHolder: ItemHolder, index: Int) {
            val file: File? = filesList?.get(index)
            val fileName = file?.name

            var numImg = file?.listFiles()?.size
            for (file in file?.listFiles()!!){
                Log.d("BANANAPIE", "important: ${file.nameWithoutExtension} $numImg")

            }

            if (file.listFiles().isNotEmpty()){
                numImg = file.listFiles().size-1

                for (image in file.listFiles()){
                    if (image.nameWithoutExtension == "stitch"){
                        Log.d("BANANAPIE", "HHHHH")
                        numImg = file.listFiles().size-2
                    }
                }

            }
            mItemHolder.fileListItemTextView.text = "Mission ${index+1} - $fileName - $numImg images"
            mItemHolder.itemView.tag = index

        }

    }

    fun returnSelectedFile(file: File){
        Log.d("BANANAPIE", file.toString())

        (activity as PhotoStitcherActivity).populateRecyclerView(file)

    }


}