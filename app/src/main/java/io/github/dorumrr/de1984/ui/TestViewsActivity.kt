package io.github.dorumrr.de1984.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.ui.firewall.FirewallFragmentViews
import io.github.dorumrr.de1984.ui.packages.PackagesFragmentViews
import io.github.dorumrr.de1984.ui.settings.SettingsFragmentViews

/**
 * Temporary test activity to verify Views fragments work
 * This will be removed once we fully migrate to Views
 */
class TestViewsActivity : AppCompatActivity() {

    private lateinit var menuLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a simple menu to switch between screens
        menuLayout = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Settings button
        Button(this).apply {
            text = "Test Settings Screen"
            setOnClickListener {
                menuLayout.visibility = View.GONE
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, SettingsFragmentViews())
                    .addToBackStack(null)
                    .commit()
            }
            menuLayout.addView(this)
        }

        // Firewall button
        Button(this).apply {
            text = "Test Firewall Screen"
            setOnClickListener {
                menuLayout.visibility = View.GONE
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, FirewallFragmentViews())
                    .addToBackStack(null)
                    .commit()
            }
            menuLayout.addView(this)
        }

        // Packages button
        Button(this).apply {
            text = "Test Packages Screen"
            setOnClickListener {
                menuLayout.visibility = View.GONE
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, PackagesFragmentViews())
                    .addToBackStack(null)
                    .commit()
            }
            menuLayout.addView(this)
        }

        setContentView(menuLayout)

        // Show menu when back stack is empty
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                menuLayout.visibility = View.VISIBLE
            }
        }
    }
}

