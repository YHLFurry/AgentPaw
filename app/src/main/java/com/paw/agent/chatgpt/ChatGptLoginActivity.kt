package com.paw.agent.chatgpt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.paw.agent.AgentPawApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatGptLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = ChatGptAuth.get(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        val title = TextView(this).apply { text = "ChatGPT 订阅直连（测试版）"; textSize = 24f }
        val intro = TextView(this).apply {
            text = "在手机浏览器登录并授权此测试应用使用 ChatGPT 套餐。对话、截图与工具结果将由手机直接通过 HTTPS 发给 OpenAI，不经过电脑。登录凭证使用 Android Keystore 加密保存在本机，退出登录可清除。模型和额度以账号可用权限为准。\n\n登录期间请勿强制关闭 AgentPaw；完成后返回此页。"; textSize = 16f
        }
        val status = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 24) }
        val login = Button(this).apply { text = "Continue with ChatGPT" }
        val logout = Button(this).apply { text = "退出登录" }
        val back = Button(this).apply { text = "返回模型设置"; setOnClickListener { finish() } }
        listOf(title, intro, status, login, logout, back).forEach { layout.addView(it) }
        setContentView(layout)
        login.setOnClickListener {
            login.isEnabled = false
            val app = application as AgentPawApplication
            app.container.appScope.launch {
                try {
                    val attempt = auth.prepareLogin()
                    try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attempt.url)))
                    }; attempt.awaitCallback() } finally { attempt.cancel() }
                } catch (e: Exception) { auth.reportFailure(e) /* Never log OAuth URLs or credentials. */ }
                finally { runOnUiThread { login.isEnabled = true } }
            }
        }
        logout.setOnClickListener { lifecycleScope.launch { auth.signOut() } }
        lifecycleScope.launch {
            while (true) {
                status.text = (if (auth.signedIn()) "已连接：${auth.accountLabel()}\n" else "尚未连接 ChatGPT\n") + auth.status
                logout.isEnabled = auth.signedIn() && login.isEnabled
                delay(1000)
            }
        }
    }
}
