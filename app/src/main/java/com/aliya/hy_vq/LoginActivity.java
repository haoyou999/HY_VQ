package com.aliya.hy_vq;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

/**
 * 登录页 — 用户名 + 密码（可选）即可进入。
 * <p>
 * 首次使用时自动由 {@link SignatureManager#initializeIdentity} 生成 uid/uuid/password；
 * 再次进入时如果已设置过密码则校验密码，否则直接通过。
 * 所有身份数据统一委托给 SignatureManager，LoginActivity 不再直接操作 SharedPreferences。
 */
public class LoginActivity extends AppCompatActivity {
    private EditText usernameInput, passwordInput;
    private TextView loginHint;
    private MaterialButton btnLogin;
    private SignatureManager sm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用主题色叠加（与主界面一致）
        ThemeHelper.applyThemeColor(this);
        setContentView(R.layout.activity_login);

        sm = new SignatureManager(this);
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);

        // 已登录用户直接进入主界面
        if (prefs.getBoolean("has_logged_in", false) && !sm.getUid().isEmpty()) {
            goToMain();
            return;
        }

        usernameInput = findViewById(R.id.login_username);
        passwordInput = findViewById(R.id.login_password);
        loginHint = findViewById(R.id.login_hint);
        btnLogin = findViewById(R.id.btn_login);

        // 预填上次的用户名
        String savedUser = prefs.getString("username", "");
        if (!savedUser.isEmpty()) usernameInput.setText(savedUser);
        // 密码从不预填

        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (username.isEmpty()) {
            username = "HY_VQ用户";
        }

        // 校验密码（已有身份时；getUid() 返回 "" 而非 null，故只需判空）
        if (!sm.getUid().isEmpty()) {
            // 已有身份，校验密码
            if (!sm.verifyPassword(password)) {
                showHint("密码错误，请重试");
                return;
            }
        }

        // 初始化/更新身份
        sm.initializeIdentity(username, password);

        Toast.makeText(this, "欢迎, " + sm.getUsername(), Toast.LENGTH_SHORT).show();
        goToMain();
    }

    private void showHint(String msg) {
        loginHint.setText(msg);
        loginHint.setVisibility(View.VISIBLE);
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
