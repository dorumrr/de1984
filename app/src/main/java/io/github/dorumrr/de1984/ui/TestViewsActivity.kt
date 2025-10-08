package io.github.dorumrr.de1984.ui

import android.os.Bundle
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
        setContentView(R.layout.activity_test_views)

        if (savedInstanceState == null) {
            // Test Firewall fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FirewallFragmentViews())
                .commit()

            // To test Settings, uncomment:
            // .replace(R.id.fragment_container, SettingsFragmentViews())
        }
    }
}

