package com.yg.wifiadb.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.yg.wifiadb.R;
import com.yg.wifiadb.util.UiUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 底部弹出的 ADB shell 面板：预设命令、自由输入、输出区。 */
public class CommandSheet extends BottomSheetDialog {

    /** 命令执行器：由调用方注入（TCP 或 USB 会话）。 */
    public interface CommandRunner {
        String run(String command) throws Exception;
    }

    private static final String[] PRESETS = {
            "getprop ro.product.model",
            "getprop ro.build.version.release",
            "wm size",
            "wm density",
            "dumpsys battery | grep level",
            "pm list packages -3",
            "input keyevent 3",
            "df -h /data",
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private CommandRunner runner;
    private TextView tvOutput;
    private EditText etCommand;
    private MaterialButton btnRun;
    private boolean running;

    public CommandSheet(@NonNull Context context, String title, String subtitle) {
        super(context);
        View root = LayoutInflater.from(context).inflate(R.layout.sheet_commands, null);
        setContentView(root);

        FrameLayout sheet = findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundColor(Color.TRANSPARENT);
        }

        TextView tvSheetTitle = root.findViewById(R.id.tvSheetTitle);
        TextView tvSheetSub = root.findViewById(R.id.tvSheetSub);
        tvOutput = root.findViewById(R.id.tvOutput);
        etCommand = root.findViewById(R.id.etCommand);
        btnRun = root.findViewById(R.id.btnRun);
        LinearLayout commandList = root.findViewById(R.id.commandList);

        tvSheetTitle.setText(title == null || title.isEmpty() ? "ADB 命令行" : title);
        tvSheetSub.setText(subtitle == null ? "" : subtitle);

        btnRun.setOnClickListener(v -> submit());
        etCommand.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_GO) {
                submit();
                return true;
            }
            return false;
        });

        buildPresets(context, commandList);
        append("已连接，可直接执行 shell 命令。\n");
    }

    public void setRunner(CommandRunner runner) {
        this.runner = runner;
    }

    private void buildPresets(Context context, LinearLayout parent) {
        int pad = (int) UiUtils.dp(context, 11);
        for (final String preset : PRESETS) {
            TextView chip = new TextView(context);
            chip.setText(preset);
            chip.setTextSize(11.5f);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextColor(UiUtils.color(context, R.color.text_primary));
            chip.setBackgroundResource(R.drawable.bg_pill_outline);
            chip.setPadding(pad, pad / 2, pad, pad / 2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) UiUtils.dp(context, 6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                etCommand.setText(preset);
                etCommand.setSelection(preset.length());
                execute(preset);
            });
            parent.addView(chip);
        }
    }

    private void submit() {
        String command = etCommand.getText().toString().trim();
        if (command.isEmpty()) {
            etCommand.setError("请输入命令");
            return;
        }
        execute(command);
    }

    private void execute(final String command) {
        if (runner == null) {
            append("错误：会话尚未就绪\n");
            return;
        }
        if (running) {
            append("请等待上一条命令执行完成\n");
            return;
        }
        running = true;
        btnRun.setEnabled(false);
        append("\n$ " + command + "\n");
        executor.execute(() -> {
            String result;
            try {
                String out = runner.run(command);
                result = out == null || out.trim().isEmpty() ? "(无输出)" : out.trim();
            } catch (Exception e) {
                result = "错误：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String text = result;
            tvOutput.post(() -> {
                append(text + "\n");
                running = false;
                btnRun.setEnabled(true);
                scrollToBottom();
            });
        });
    }

    private void append(String text) {
        tvOutput.append(text);
        scrollToBottom();
    }

    private void scrollToBottom() {
        View parent = (View) tvOutput.getParent();
        while (parent != null && !(parent instanceof ScrollView)) {
            if (!(parent.getParent() instanceof View)) {
                return;
            }
            parent = (View) parent.getParent();
        }
        if (parent instanceof ScrollView) {
            final ScrollView scrollView = (ScrollView) parent;
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        } else {
            tvOutput.post(() -> {
                int scroll = tvOutput.getLayout() == null ? 0
                        : tvOutput.getLayout().getLineTop(tvOutput.getLineCount())
                        - tvOutput.getHeight();
                if (scroll > 0) {
                    tvOutput.scrollTo(0, scroll);
                }
            });
        }
    }

    @Override
    public void dismiss() {
        executor.shutdownNow();
        super.dismiss();
    }
}
