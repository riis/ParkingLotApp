package com.dji.droneparking.core

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.dji.droneparking.R
import com.ncorti.slidetoact.SlideToActView

/**
 * This is the second view the user will see in the app. Its purpose is to display brief instructions to the user on
 * how to use the app. The user can then slide the "Slide-to-unlock" widget to navigate to FlightPlanActivity.kt
 */
class TutorialFragment : Fragment(), SlideToActView.OnSlideCompleteListener {

    private lateinit var getStartedButton: SlideToActView //slider widget

    //creating the fragment view from the fragment_tutorial.xml layout
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_tutorial, container, false)

        //referencing the slider view using its resource id
        getStartedButton = view.findViewById(R.id.get_started_button) as SlideToActView

        return view
    }

    //initializing the slider UI
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        getStartedButton.onSlideCompleteListener = this //giving the slider a listener
    }

    //Listens to when the user completes sliding the slider
    override fun onSlideComplete(view: SlideToActView) {
        //app navigates to FlightPlanActivity.kt
        val intent = Intent(activity, FlightPlanActivity::class.java)
        startActivity(intent)
    }

}