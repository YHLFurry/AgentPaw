package com.paw.agent.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.paw.agent.R

/** Native Views keep the overlay independent of Activity/Compose lifecycle owners. */
@SuppressLint("ViewConstructor") // Created by the service; never inflated from XML.
internal class AgentFloatingPanel(
    context: Context,
    private val onAction: () -> Unit,
    private val onOpenChat: () -> Unit,
    private val onSizeChanged: () -> Unit,
) : LinearLayout(context) {
    private val ink = Color.rgb(244, 247, 252)
    private val muted = Color.rgb(167, 181, 201)
    private val running = Color.rgb(94, 234, 212)
    private val paused = Color.rgb(253, 211, 123)
    private var state = AgentExecutionController.state.value
    var isExpanded = true
        private set

    private val logo = ImageButton(context).apply {
        id = R.id.floating_toggle
        backgroundTintList = null
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = ripple(Color.rgb(36, 57, 65), 16)
        setOnClickListener { setExpanded(!isExpanded) }
    }
    private val title = label(13f, ink, bold = true)
    private val status = label(11f, running).apply {
        id = R.id.floating_status
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val action = Button(context).apply {
        id = R.id.floating_action
        backgroundTintList = null
        isAllCaps = false
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        minWidth = dp(80)
        minimumWidth = dp(80)
        maxWidth = dp(120)
        maxLines = 2
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onAction()
        }
    }
    private val detail = label(13f, ink).apply {
        id = R.id.floating_detail
        maxLines = 2
        setPadding(dp(8), dp(8), dp(8), dp(4))
    }
    private val steps = label(11f, muted).apply {
        id = R.id.floating_steps
        setPadding(dp(8), 0, dp(4), 0)
        maxLines = 2
    }
    private val footer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(28, 38, 53), Color.rgb(16, 24, 38)),
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.rgb(65, 81, 101))
        }
        elevation = dp(8).toFloat()

        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
                addView(title)
                addView(status)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        footer.addView(steps, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(TextView(context).apply {
            id = R.id.floating_open
            text = context.getString(R.string.floating_open_chat)
            textSize = 12f
            setTextColor(ink)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            background = ripple(Color.TRANSPARENT, 12)
            setOnClickListener { onOpenChat() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        footer.addView(ImageButton(context).apply {
            id = R.id.floating_collapse
            backgroundTintList = null
            setImageResource(R.drawable.ic_floating_chevron)
            imageTintList = ColorStateList.valueOf(muted)
            contentDescription = context.getString(R.string.floating_collapse)
            background = ripple(Color.TRANSPARENT, 12)
            setOnClickListener { setExpanded(false) }
        }, LayoutParams(dp(48), dp(48)))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            when {
                !isExpanded -> setExpanded(true)
                state.isPaused -> onAction()
                else -> onOpenChat()
            }
        }
        render(state)
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        render(state)
        onSizeChanged()
    }

    fun render(value: AgentExecutionController.ExecutionState) {
        state = value
        val active = value.isRunning
        val waiting = !active && value.isPaused
        val accent = when { active -> running; waiting -> paused; else -> muted }
        val stateLabel = context.getString(when {
            active -> R.string.floating_running
            waiting -> R.string.floating_paused
            else -> R.string.floating_finished
        })
        val stepLabel = if (value.maxSteps > 0) {
            context.getString(R.string.floating_step_budget, value.currentStep, value.maxSteps)
        } else {
            context.getString(R.string.floating_step_unlimited, value.currentStep)
        }
        title.text = if (isExpanded) context.getString(R.string.app_name) else stateLabel
        status.text = if (isExpanded) stateLabel else stepLabel
        status.setTextColor(accent)
        detail.text = value.currentAction.ifBlank { context.getString(R.string.floating_preparing) }
        detail.visibility = if (isExpanded) View.VISIBLE else View.GONE
        steps.text = stepLabel
        footer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        logo.setImageResource(if (isExpanded) R.drawable.ic_launcher_foreground else R.drawable.ic_floating_chevron)
        logo.rotation = if (isExpanded) 0f else 180f
        logo.imageTintList = if (isExpanded) null else ColorStateList.valueOf(accent)
        logo.contentDescription = context.getString(if (isExpanded) R.string.floating_collapse else R.string.floating_expand)
        action.text = context.getString(when {
            active -> R.string.chat_stop
            waiting -> R.string.floating_resume
            else -> R.string.floating_view
        })
        action.contentDescription = context.getString(when {
            active -> R.string.floating_stop_description
            waiting -> R.string.floating_resume_description
            else -> R.string.floating_open_chat
        })
        action.setTextColor(if (waiting) Color.rgb(38, 29, 12) else ink)
        action.background = ripple(when {
            active -> Color.rgb(185, 43, 62)
            waiting -> paused
            else -> Color.rgb(51, 65, 85)
        }, 16)
        contentDescription = null // Keep each action independently accessible.
    }

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun ripple(color: Int, radius: Int): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), shape, GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radius).toFloat()
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
