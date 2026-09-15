package com.example.helloandroid;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Main entry point of the application.
 *
 * This Activity is declared as the launcher activity in AndroidManifest.xml,
 * so it is the first screen shown when the app starts.
 *
 * NOTE: the lecture slides import `android.support.v7.app.AppCompatActivity`.
 * That support library has been retired by Google and no longer compiles, so
 * this project uses its direct replacement, `androidx.appcompat.app.AppCompatActivity`.
 * Everything else is identical to the slides.
 */
public class MainActivity extends AppCompatActivity {

    /**
     * Called automatically when the Activity is created.
     * Sets up the initial View for this Activity.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // R.layout.activity_main refers to res/layout/activity_main.xml
        setContentView(R.layout.activity_main);
    }
}
