package net.k1lllagt.andraxng.ui

import android.app.Activity
import android.os.Bundle

class LimitsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Honest limits"
        val pg = Ui.page(this)
        pg.col.addView(Ui.title(this, "Honest limits"))
        pg.col.addView(Ui.body(this, HelpText.LIMITS))
        pg.col.addView(Ui.heading(this, "Running in the background"))
        pg.col.addView(Ui.mono(this, HelpText.BACKGROUND))
        setContentView(pg.scroll)
    }
}
