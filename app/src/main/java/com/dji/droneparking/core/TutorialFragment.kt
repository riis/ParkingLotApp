
package com.dji.droneparking.core

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.dji.droneparking.R
import com.ncorti.slidetoact.SlideToActView


class TutorialFragment : Fragment(), SlideToActView.OnSlideCompleteListener {

    private lateinit var getStartedButton: SlideToActView

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment

        val view = inflater.inflate(R.layout.fragment_tutorial, container, false)

        getStartedButton = view.findViewById(R.id.get_started_button) as SlideToActView

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        getStartedButton.onSlideCompleteListener = this
    }

    override fun onSlideComplete(view: SlideToActView) {
        val intent = Intent(activity, FlightPlanActivity::class.java)
        startActivity(intent)
    }

}