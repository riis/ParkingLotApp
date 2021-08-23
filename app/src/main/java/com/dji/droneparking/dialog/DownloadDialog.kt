package com.dji.droneparking.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.dji.droneparking.databinding.DownloadDialogBinding
//import com.riis.etaDetroitkotlin.R


class DownloadDialog(val text: String) : DialogFragment() {

    private var _binding: DownloadDialogBinding? = null
    private val binding get() = _binding!!
//    private var text = "Starting Download..."

    //CREATING THE DIALOG
    //---------------------
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DownloadDialogBinding.inflate(LayoutInflater.from(context))
        Log.d("BANANAPIE", binding.toString())

        val builder = AlertDialog.Builder(requireActivity())
        builder.setView(binding.root)
        builder.setCancelable(false)


        val dialog: Dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)



        binding.textView.text = text



        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        return dialog
    }

    fun setProgress(progress: Int){
        binding.progressBar.progress = progress
    }

    fun setText(text: String){
        binding.textView.text = text
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}