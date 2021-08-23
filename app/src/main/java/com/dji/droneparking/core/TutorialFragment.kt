package com.dji.droneparking.core

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dji.droneparking.R
import android.content.pm.ActivityInfo

import android.app.Activity
import android.content.Intent
import android.widget.Button


class TutorialFragment : Fragment() {

    private lateinit var getStartedButton : Button
    private lateinit var cameraBtn: Button
    private lateinit var locateBtn: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_tutorial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        getStartedButton = view.findViewById(R.id.get_started_button)
        cameraBtn = view.findViewById(R.id.camera_button)
        locateBtn = view.findViewById(R.id.locate_button)



        getStartedButton.setOnClickListener{

            locateBtn.visibility = View.VISIBLE
            cameraBtn.visibility = View.VISIBLE
//            showClearMemoryDialog()

            val intent = Intent(activity, FlightPlanActivity::class.java)
            startActivity(intent)
        }
    }
}