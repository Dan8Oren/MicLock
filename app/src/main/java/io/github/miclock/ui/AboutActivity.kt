package io.github.miclock.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.miclock.R

/**
 * AboutActivity displays information about the Mic-Lock application including
 * version, description, open source information, and license details.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Set up custom back button
        val backButton = findViewById<android.widget.ImageButton>(R.id.backButton)
        backButton.setOnClickListener {
            finish()
        }

        setupVersionInfo()
        setupGitHubLink()
    }

    /**
     * Sets up the version information display
     */
    private fun setupVersionInfo() {
        val versionText = findViewById<TextView>(R.id.versionText)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)

            @Suppress("DEPRECATION")
            val versionCode = packageInfo.versionCode
            versionText.text = getString(
                R.string.about_version_format,
                packageInfo.versionName,
                versionCode,
            )
        } catch (e: Exception) {
            versionText.text = getString(R.string.about_version_format, "Unknown", 0)
        }
    }

    /**
     * Sets up the clickable GitHub link
     */
    private fun setupGitHubLink() {
        val githubLinkText = findViewById<TextView>(R.id.githubLinkText)
        val githubUrl = getString(R.string.github_url)

        val spannableString = SpannableString(githubUrl)
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                openGitHubUrl(githubUrl)
            }
        }

        spannableString.setSpan(
            clickableSpan,
            0,
            githubUrl.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        githubLinkText.text = spannableString
        githubLinkText.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * Opens the GitHub repository URL in a browser
     */
    private fun openGitHubUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            // Handle case where no browser is available
            e.printStackTrace()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
