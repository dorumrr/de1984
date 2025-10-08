package io.github.dorumrr.de1984.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.ui.firewall.FirewallFragmentViews
import io.github.dorumrr.de1984.ui.settings.SettingsFragmentViews

/**
 * Temporary test activity to verify Views fragments work
 * This will be removed once we fully migrate to Views
 */
class TestViewsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a simple menu to switch between screens
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Settings button
        Button(this).apply {
            text = "Test Settings Screen"
            setOnClickListener {
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, SettingsFragmentViews())
                    .addToBackStack(null)
                    .commit()
            }
            layout.addView(this)
        }

        // Firewall button
        Button(this).apply {
            text = "Test Firewall Screen"
            setOnClickListener {
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, FirewallFragmentViews())
                    .addToBackStack(null)
                    .commit()
            }
            layout.addView(this)
        }

        setContentView(layout)
    }
}

