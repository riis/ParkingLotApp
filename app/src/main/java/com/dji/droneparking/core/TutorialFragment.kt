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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment

        val view = inflater.inflate(R.layout.fragment_tutorial, container, false)

        getStartedButton = view.findViewById(R.id.get_started_button)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        getStartedButton.setOnClickListener{

//            showClearMemoryDialog()

            val intent = Intent(activity, FlightPlanActivity::class.java)
            startActivity(intent)
        }
    }
}