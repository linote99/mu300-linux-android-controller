package org.linote.mu300controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android USB-host controller. Root and Magisk commands run on the USB-connected target only. */
public final class MainActivity extends Activity {
    private static final String RELEASE_API = "https://api.github.com/repos/dikeckaan/mu300-linux/releases/latest";
    private static final String GUIDE_URL = "https://kaandikec.com/mu300-linux/zh/web-installer/";
    private static final String UFI_PACKAGE = "top.linote.ufiinstaller";
    private static final String UFI_GUIDE_URL = "https://www.linote.top/posts/lino-ufi-tools-android/";
    private static final String BLOG_URL = "https://www.linote.top/";
    private static final String GITHUB_URL = "https://github.com/linote99";
    private static final String ACTION_USB_PERMISSION = "org.linote.mu300controller.USB_PERMISSION";
    private static final String EXTRA_DEVICE_NAME = "device_name";
    private static final String ROOT_STAGE = "/data/adb/mu300-otg-installer";
    private static final int REQUEST_EXPORT_RUNTIME_LOG = 4107;
    private static final int REQUEST_LOCAL_CLEANUP = 4108;
    private static final long MAX_ZIP_BYTES = 300L * 1024L * 1024L;
    private static final long RESERVED_DATA_BYTES = 512L * 1024L * 1024L;
    private static final int BG = Color.rgb(244, 247, 247);
    private static final int CARD = Color.WHITE;
    private static final int INK = Color.rgb(24, 40, 47);
    private static final int MUTED = Color.rgb(93, 111, 119);
    private static final int TEAL = Color.rgb(8, 126, 120);
    private static final int RED = Color.rgb(171, 49, 57);
    private static final String[] STEP_NAMES = {"设备连接", "系统与 ZIP", "目标传输", "计划与安装", "高级功能", "日志"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<UsbAdbTransport.Candidate> usbCandidates = new ArrayList<>();
    private UsbManager usbManager;
    private SharedPreferences prefs;
    private SharedPreferences uiPrefs;
    private AdbConnection adb;
    private volatile AdbConnection helperStageConnection;
    private volatile AdbConnection stagedConnection;
    private DeviceInfo target;
    private Release latest;
    private File verifiedZip;
    private String verifiedName = "";
    private String verifiedSha = "";
    private String stagedZipPath = "";
    private String stagedName = "";
    private String stagedSha = "";
    private String stageId = "";
    private String inboxPath = "";
    private JSONObject webStatus;
    private MakeRoomTransaction.Record waitingResume;
    private boolean resumeValidated;
    private String resumeValidationError = "";
    private boolean remoteJobPending;
    private String remoteJobKind = "", remoteJobName = "";
    private WebInstallerClient.ZipManifest stagedManifest;
    private PlanSession planSession;
    private volatile boolean busy;
    private volatile boolean installInProgress;
    private volatile boolean installWasAttempted;
    private boolean alive = true;
    private boolean initializingSpinners;
    private boolean advancedOn;
    private int titleTaps;
    private long lastTitleTap;
    private int friendTitleTaps;
    private long lastFriendTitleTap;
    private Toast tapToast;

    private LinearLayout page, stepHost, tabStrip, stepNav, aboutPage;
    private ScrollView stepScroll;
    private HorizontalScrollView tabScroller;
    private final List<LinearLayout> stepPages = new ArrayList<>();
    private final List<TextView> stepTabs = new ArrayList<>();
    private int currentStep;
    private int returnStep;
    private boolean aboutOpen;
    private TextView stepLine, aboutButton;
    private Button previousStepButton, nextStepButton;
    private Spinner deviceSpinner, osSpinner, kernelSpinner;
    private TextView deviceView, releaseView, fileView, stageView, statusView, logView;
    private ProgressBar progress;
    private Button scanButton, connectButton, releaseButton, downloadButton, pushButton, installButton, planButton, makeRoomButton;
    private Button exportRuntimeLogButton;
    private Button targetScanButton, targetCleanButton;
    private TextView targetCleanupView;
    private TargetStageCleanup.Inventory targetStageInventory;
    private AdbConnection targetStageInventoryConnection;
    private TextView helperView;
    private StringBuilder logs = new StringBuilder();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && name != null) connectUsbNow(name);
                else setStatus("未获得 USB 访问权限；请重新连接并允许控制端访问目标设备。");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                refreshUsbList();
                if (adb != null && adb.isClosed()) setStatus("USB 已断开。若安装曾开始，结果不确定；不要盲目重试。");
            }
        }
    };

    private static final class Asset {
        final String name, url;
        final long size;
        Asset(String name, String url, long size) { this.name = name; this.url = url; this.size = size; }
    }
    private static final class Release {
        final String tag;
        final String checksumUrl;
        final java.util.Map<String, Asset> assets;
        Release(String tag, String checksumUrl, java.util.Map<String, Asset> assets) {
            this.tag = tag; this.checksumUrl = checksumUrl; this.assets = assets;
        }
    }
    private static final class DeviceInfo {
        String model = "未知", device = "未知", android = "未知", slot = "未知", bootCompleted = "未知";
        String magisk = "未知", lock = "未知", endpoint = "";
        boolean root, magiskOk, supportedHint;
        boolean ready() { return root && magiskOk; }
        String brief() {
            return "型号：" + model + "（" + device + ")\nAndroid：" + android + "\n启动槽：" + slot
                    + "\n目标机型提示：" + (supportedHint ? "看起来是受支持型号；仍由官方 ZIP 最终判定" : "型号字符串未识别；官方 ZIP 必须最终判定")
                    + "\nMagisk：" + magisk + (magiskOk ? "（满足 26+）" : "（需 26+，未通过）")
                    + "\nBootloader：" + lock + "\nRoot 授权：" + (root ? "已授予" : "未确认")
                    + "\nADB：" + endpoint;
        }
    }

    private static final class PlanSession {
        final String zipName, zipSha, answerText, password;
        final int attempts;
        final boolean linuxDefault, hotspot, gpu;
        final boolean passwordSet, regionOverwrite;
        final WebInstallerClient.ZipManifest manifest;
        final Map<String,String> values;
        final JSONObject statusSnapshot;
        PlanSession(String zipName,String zipSha,String answerText,String password,WebInstallerClient.ZipManifest manifest,Map<String,String> values,
                    boolean regionOverwrite,int attempts,boolean linuxDefault,boolean hotspot,boolean gpu,JSONObject statusSnapshot) {
            this.zipName=zipName;this.zipSha=zipSha;this.answerText=answerText;this.password=password;
            this.passwordSet=password!=null&&!password.isEmpty();this.manifest=manifest;this.values=values;this.regionOverwrite=regionOverwrite;
            this.attempts=attempts;this.linuxDefault=linuxDefault;this.hotspot=hotspot;this.gpu=gpu;this.statusSnapshot=statusSnapshot;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppRuntimeLog.initialize(this, BuildInfo.VERSION_NAME);
        AppRuntimeLog.record("LIFECYCLE", "MainActivity onCreate");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setTitle("MU300 Linux OTG 控制器");
        prefs = getSharedPreferences("remote-install-state", MODE_PRIVATE);
        uiPrefs = getSharedPreferences("ui", MODE_PRIVATE);
        advancedOn = uiPrefs.getBoolean("advanced", false);
        String previousInstall = prefs.getString("install_state", "");
        installWasAttempted = "pending".equals(previousInstall) || "unknown".equals(previousInstall);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
        buildUi(state);
        appendLog("MU300 Linux OTG 控制器 v" + BuildInfo.VERSION_NAME);
        appendLog("控制端只负责 USB ADB、下载、校验和推送；su/Magisk 命令仅在 USB 连接的目标设备执行。");
        if (installWasAttempted) setStatus("检测到上次安装开始后应用未能确认结果。为避免重复写入，本应用已锁定新的安装；先按官方说明检查目标状态。");
        refreshUsbList();
        refreshRelease();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("currentStep", currentStep);
        super.onSaveInstanceState(out);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCAL_CLEANUP) {
            if (resultCode == RESULT_OK && waitingResume == null && verifiedZip != null && !verifiedZip.exists()) {
                verifiedZip = null; verifiedName = ""; verifiedSha = "";
                stagedConnection = null; stagedZipPath = ""; stagedName = ""; stagedSha = ""; stagedManifest = null;
            }
            targetStageInventory = null; targetStageInventoryConnection = null;
            renderSelection();
            return;
        }
        if (requestCode != REQUEST_EXPORT_RUNTIME_LOG) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            AppRuntimeLog.record("EXPORT", "runtime log export cancelled");
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "w")) {
            if (out == null) throw new java.io.IOException("无法创建日志文件");
            AppRuntimeLog.record("EXPORT", "destination selected; writing runtime log");
            out.write(AppRuntimeLog.readForExport(this));
            out.flush();
            Toast.makeText(this, "运行日志已导出。", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppRuntimeLog.recordThrowable("ERROR", "runtime log export failed", e);
            Toast.makeText(this, "导出运行日志失败。", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onBackPressed() {
        if (busy || installInProgress) {
            Toast.makeText(this, "当前 OTG 操作进行中；不要退出或拔线。", Toast.LENGTH_LONG).show();
            return;
        }
        if (aboutOpen) { showStep(returnStep); return; }
        if (currentStep > 0) { showStep(currentStep - 1); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        AppRuntimeLog.record("LIFECYCLE", "MainActivity onDestroy");
        alive = false;
        try { unregisterReceiver(usbReceiver); } catch (RuntimeException ignored) { }
        if (!installInProgress && adb != null) adb.close();
        worker.shutdown();
        super.onDestroy();
    }

    private void buildUi(Bundle state) {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        LinearLayout header = column(); header.setPadding(dp(18), dp(38), dp(18), dp(6));
        LinearLayout titleRow = new LinearLayout(this); titleRow.setOrientation(LinearLayout.HORIZONTAL); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("MU300 Linux", 25, INK, true); title.setOnClickListener(v -> titleTapped()); titleRow.addView(title, weight(1));
        aboutButton = text("关于", 13, TEAL, true); aboutButton.setGravity(Gravity.CENTER); aboutButton.setPadding(dp(13), dp(7), dp(13), dp(7));
        aboutButton.setBackground(roundRect(CARD, Color.rgb(223,231,231), dp(18))); aboutButton.setOnClickListener(v -> toggleAboutPage());
        titleRow.addView(aboutButton, params(-2, -2)); header.addView(titleRow, params(-1, -2));
        TextView subtitle = text("USB / OTG 控制端 · v" + BuildInfo.VERSION_NAME, 13, MUTED, false);
        LinearLayout.LayoutParams sp = params(-1, -2); sp.topMargin = dp(2); header.addView(subtitle, sp);
        stepLine = text("步骤 1 / 6 · 设备连接", 12, TEAL, true); stepLine.setPadding(0, dp(8), 0, dp(2)); header.addView(stepLine);
        statusView = text("准备中。", 13, INK, true); statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackground(roundRect(Color.WHITE, Color.rgb(223,231,231), dp(10)));
        header.addView(statusView, topParams(dp(4)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgress(0); header.addView(progress, topParams(dp(7)));
        root.addView(header, params(-1, -2));

        tabScroller = new HorizontalScrollView(this); tabScroller.setHorizontalScrollBarEnabled(false);
        tabScroller.setFillViewport(false); tabScroller.setBackgroundColor(BG);
        tabStrip = new LinearLayout(this); tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(dp(12), dp(4), dp(12), dp(8)); tabScroller.addView(tabStrip);
        root.addView(tabScroller, params(-1, -2));

        stepScroll = new ScrollView(this); stepScroll.setFillViewport(true); stepScroll.setBackgroundColor(BG);
        stepHost = column(); stepScroll.addView(stepHost);
        root.addView(stepScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        stepNav = new LinearLayout(this); stepNav.setOrientation(LinearLayout.HORIZONTAL); stepNav.setGravity(Gravity.CENTER_VERTICAL);
        stepNav.setPadding(dp(14), dp(8), dp(14), dp(10)); stepNav.setBackgroundColor(CARD);
        previousStepButton = button("上一步"); previousStepButton.setOnClickListener(v -> previousStep());
        nextStepButton = button("下一步"); nextStepButton.setOnClickListener(v -> nextStep());
        stepNav.addView(previousStepButton, weight(1));
        LinearLayout.LayoutParams np = weight(1); np.leftMargin = dp(8); stepNav.addView(nextStepButton, np);
        root.addView(stepNav, params(-1, -2));
        setContentView(root);

        for (int i = 0; i < STEP_NAMES.length; i++) {
            LinearLayout p = column(); p.setPadding(dp(18), dp(10), dp(18), dp(24)); stepPages.add(p);
        }
        buildTabs();

        page = stepPages.get(0); addStepHeading("步骤 1 · 连接目标设备", "先确认目标与控制手机的角色不同，再进行 USB ADB 只读检查。");
        LinearLayout scope = card("方向与适用范围");
        addText(scope, "本 APK 安装在另一台 Android 手机上，作为 USB Host 通过 OTG 连接目标 F50 / MU300 / U30 Air。它不会在控制手机本机调用 su；目标设备需已开启 USB 调试、已 root，并安装 Magisk 26 或更新版本。", 14, INK, false, 0, 0);
        addText(scope, "U30 Air 与 Android 手机 OTG 组合尚未在真实设备验证。首次 ADB 主机密钥授权通常要在目标设备确认；U30 Air 无屏时须提前授权。OTG 供电要稳定。", 13, RED, true, 0, dp(8));
        LinearLayout dev = card("USB 连接目标设备");
        addText(dev, "目标 Android 开启 USB 调试后，用支持 USB Host 的控制手机和 OTG 线连接。列表只显示带 ADB 接口的 USB 设备。", 13, MUTED, false, 0, dp(8));
        deviceSpinner = new Spinner(this); dev.addView(deviceSpinner, params(-1, dp(48)));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        scanButton = button("扫描 USB"); scanButton.setOnClickListener(v -> refreshUsbList());
        connectButton = button("连接并检查目标"); connectButton.setOnClickListener(v -> requestOrConnect());
        row.addView(scanButton, weight(1)); LinearLayout.LayoutParams cp = weight(1); cp.leftMargin = dp(8); row.addView(connectButton, cp);
        dev.addView(row, topParams(dp(8)));
        deviceView = text("尚未连接目标。", 13, MUTED, false); deviceView.setPadding(dp(2), dp(10), dp(2), 0); dev.addView(deviceView, params(-1, -2));

        page = stepPages.get(1); addStepHeading("步骤 2 · 选择系统与官方 ZIP", "选择系统和内核；下载后先在控制手机校验官方 SHA-256。");
        LinearLayout select = card("选择官方系统 ZIP");
        osSpinner = spinner(new String[]{"OpenWrt", "OpenWrt（MU300 控制面板）", "Ubuntu 24.04 LTS", "Ubuntu 26.04 LTS"});
        kernelSpinner = spinner(new String[]{"5.4（厂商内核）", "6.18 LTS", "7.2", "7.3（较新，测试较少）"});
        addText(select, "系统", 13, INK, true, 0, dp(4)); select.addView(osSpinner, topParams(dp(4)));
        addText(select, "内核", 13, INK, true, 0, dp(8)); select.addView(kernelSpinner, topParams(dp(4)));
        AdapterView.OnItemSelectedListener change = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initializingSpinners) return;
                if (parent == osSpinner) updateKernelOptions();
                invalidateSelectedFile(); renderSelection();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        osSpinner.setOnItemSelectedListener(change); kernelSpinner.setOnItemSelectedListener(change);
        releaseView = text("正在读取官方版本……", 13, MUTED, false); select.addView(releaseView, topParams(dp(10)));
        releaseButton = button("读取最新官方发布"); releaseButton.setOnClickListener(v -> refreshRelease());
        select.addView(releaseButton, topParams(dp(8)));
        LinearLayout download = card("下载到控制手机并校验");
        fileView = text("尚未下载当前选择的 ZIP。", 13, MUTED, false); download.addView(fileView, params(-1, -2));
        downloadButton = button("下载并校验官方 SHA-256"); downloadButton.setOnClickListener(v -> downloadSelected());
        download.addView(downloadButton, topParams(dp(8)));
        addText(download, "本步骤只下载到控制手机；不会写入目标分区。", 12, MUTED, false, dp(6), 0);

        page = stepPages.get(2); addStepHeading("步骤 3 · 推送并复核", "仅把已在本机校验的 ZIP 传给目标 helper；目标端还会再次校验。");
        LinearLayout transfer = card("通过 USB ADB 推送到目标");
        stageView = text("ZIP 只在本机校验通过、目标端复核通过后，才允许进入最终安装确认。", 13, MUTED, false);
        transfer.addView(stageView, params(-1, -2));
        pushButton = button("推送并由目标端复核 SHA-256"); pushButton.setOnClickListener(v -> pushToTarget());
        transfer.addView(pushButton, topParams(dp(8)));
        addText(transfer, "传输前会检查目标 /data 可用空间与 helper 状态；有任务待续接时只跟随原任务，不另起新任务。", 12, MUTED, false, dp(8), 0);

        page = stepPages.get(3); addStepHeading("步骤 4 · 只读计划与安装", "确认 Magisk 26+ 后运行 dry-run；计划通过才开放 INSTALL。");
        LinearLayout action = card("参数问卷、dry-run 计划与最终确认");
        addText(action, "目标 root 和 Magisk 26+ 校验通过后，官方 helper 才会读取状态并运行只读 dry-run。只有 dry_run=1、关键字段与 ZIP/目标状态一致后，才允许最终安装。root 命令只在 USB 连接的目标设备执行。", 13, INK, false, 0, 0);
        planButton = button("填写参数并运行只读 dry-run"); planButton.setOnClickListener(v -> beginPlanQuestionnaire());
        action.addView(planButton, topParams(dp(8)));
        installButton = button("查看已验证计划并确认 INSTALL"); installButton.setOnClickListener(v -> confirmInstall());
        action.addView(installButton, topParams(dp(8)));
        addText(action, "安装可能写 Linux region、另一个启动槽的 boot chain 与 misc。保持 OTG 供电和连接；断线后重新连接只续接同一 helper 任务，结果不明时不要盲目重试。", 13, RED, true, dp(8), 0);

        page = stepPages.get(4); addStepHeading("高级功能 · userdata 缩容后安装", "实验性危险流程；默认隐藏。恢复阶段必须用原目标、原 ZIP 和本地备份。");
        LinearLayout ufiCard = card("Lino-UFI 联动 · 格式化前备份");
        addText(ufiCard, "先用 Lino-UFI 备份 Magisk（面具）和需要恢复的其他应用安装包。它备份的是 APK，不含应用数据；照片、下载、帐号及其他个人文件还要单独备份。可通过 Lino-UFI 投屏，在目标设备上给 Shell 授予 Magisk root。", 13, INK, false, 0, dp(8));
        Button ufiButton = button(linoUfiButtonLabel()); ufiButton.setOnClickListener(v -> openLinoUfi());
        ufiCard.addView(ufiButton, topParams(dp(4)));
        LinearLayout room = card("实验性：准备内部 Linux 空间");
        addText(room, "开始前必须确认目标 Shell 已获 Magisk root 授权；连接检查会执行只读 su -c id -u，且未确认 root/Magisk 时不会开放预检。不可逆：将改写 eMMC GPT，并在 misc 写入 recovery 清除请求；目标下次重启时会清空整块 Android userdata，包括应用、照片、下载、帐号和设置。", 13, RED, true, 0, dp(8));
        addText(room, "阶段一只读检查设备身份、双份 GPT、加密状态和空闲区采样，再保存恢复流程要求的分区备份；确认 ERASE 后才建立事务。之后需单独确认 recovery 重启。格式化前用 Lino-UFI 备份 Magisk 和要恢复的软件 APK（不含 App 数据），个人文件另行备份。", 13, INK, false, 0, dp(8));
        addText(room, "阶段二恢复顺序：重新设置 Android 并开启 ADB → 用 Lino-UFI 安装 UFI-TOOLS → 在 UFI-TOOLS 中开启 ADB → 用 Lino-UFI 恢复 Magisk 和其他应用 → 用 Lino-UFI 投屏并重新给 Shell 授予 root → OTG 连接同一目标。应用核验设备、GPT、marker、原 ZIP 与本地备份后，才允许 dry-run 和 INSTALL。", 13, INK, false, 0, dp(8));
        makeRoomButton = button("查看只读预检与 Linux 空间设置"); makeRoomButton.setOnClickListener(v -> beginMakeRoom());
        room.addView(makeRoomButton, topParams(dp(8)));
        helperView = text("连接目标后读取官方 helper 状态。缩容是作者标注的实验性路径；本 APK 未在真实设备验证。", 13, MUTED, false);
        room.addView(helperView, topParams(dp(8)));

        page = stepPages.get(5); addStepHeading("过程日志", "日志会自动遮盖常见设备身份信息；复制前仍建议检查内容。");
        LinearLayout logCard = card("过程记录（已做隐私遮盖）");
        logView = text("", 12, MUTED, false); logView.setTextIsSelectable(true); logCard.addView(logView, params(-1, -2));
        Button copy = button("复制记录"); copy.setOnClickListener(v -> copyLog()); logCard.addView(copy, topParams(dp(8)));
        Button guide = button("打开官方网页安装器说明"); guide.setOnClickListener(v -> openGuide()); logCard.addView(guide, topParams(dp(8)));

        aboutPage = column(); aboutPage.setPadding(dp(18), dp(10), dp(18), dp(24));
        page = aboutPage; addStepHeading("关于与使用说明", "MU300 Linux OTG 控制器 · 版本 " + BuildInfo.VERSION_NAME);
        LinearLayout about = card("基本流程");
        addText(about, "1. 连接并检查目标设备；2. 选择系统/内核并下载官方 ZIP；3. 推送 ZIP 并在目标端复核；4. 检查参数、运行只读 dry-run，核对计划后输入 INSTALL。各步骤均可从上方标签直接查看。", 13, INK, false, 0, dp(8));
        addText(about, "本 APK 是 USB Host 控制端，不在控制手机执行 root 命令。安装任务由目标设备上的官方 helper 执行。实验性 userdata 缩容/清除尚未在真实设备验证。", 13, RED, true, 0, 0);
        LinearLayout advancedHelp = card("高级功能入口");
        addText(advancedHelp, "连点页面顶部的“MU300 Linux”标题 5 下（1.5 秒内；从第 3 下起会提示还差几次），即可显示“高级功能”页。再次连点标题 5 下可关闭；开关会记住。", 13, INK, false, 0, dp(8));
        addText(advancedHelp, "高级页包含 32GB 机型的实验性 userdata 缩容/格式化后安装。开始前须通过 Shell root 检查；用 Lino-UFI 备份 Magisk 和要恢复的应用 APK（只含安装包，不含 App 数据），照片/下载/个人文件另行备份。ERASE 和 recovery 重启分开确认；userdata 会全部清空。", 13, RED, true, 0, dp(8));
        addText(advancedHelp, "清除后：重新设置 Android 并开启 ADB → 用 Lino-UFI 安装 UFI-TOOLS，在其中开启 ADB → 用 Lino-UFI 恢复 Magisk/应用并投屏给 Shell 重新授权 root → 再连接原目标。身份、GPT、marker、原 ZIP 和本地备份验证通过后，才继续 dry-run 与 INSTALL。", 13, INK, false, 0, 0);
        Button aboutUfi = button(linoUfiButtonLabel()); aboutUfi.setOnClickListener(v -> openLinoUfi()); advancedHelp.addView(aboutUfi, topParams(dp(8)));
        LinearLayout aboutLinks = card("更多信息");
        Button aboutGuide = button("打开官方安装器说明"); aboutGuide.setOnClickListener(v -> openGuide()); aboutLinks.addView(aboutGuide, params(-1, -2));
        Button aboutLogs = button("查看过程日志"); aboutLogs.setOnClickListener(v -> showStep(5)); aboutLinks.addView(aboutLogs, topParams(dp(6)));
        exportRuntimeLogButton = button("导出运行日志"); exportRuntimeLogButton.setVisibility(View.GONE);
        exportRuntimeLogButton.setOnClickListener(v -> exportRuntimeLog()); aboutLinks.addView(exportRuntimeLogButton, topParams(dp(6)));
        LinearLayout targetCleanup = card("检测并清理目标端暂存文件");
        addText(targetCleanup, "仅检查 /data/local/tmp/mu300-web 中本应用推送的 helper 与未接收输入文件；清理前会检查目标任务。不会删除 /data/adb/mu300-web 中的活动任务、备份、ZIP 或待续装资料。", 12, MUTED, false, dp(6), 0);
        targetCleanupView = text("尚未检测。请先连接并授权目标设备。", 12, MUTED, false); targetCleanup.addView(targetCleanupView, topParams(dp(6)));
        targetScanButton = button("检测目标端文件"); targetScanButton.setOnClickListener(v -> detectTargetStageFiles()); targetCleanup.addView(targetScanButton, topParams(dp(4)));
        targetCleanButton = button("清理已检测的暂存文件"); targetCleanButton.setEnabled(false); targetCleanButton.setOnClickListener(v -> confirmTargetStageCleanup()); targetCleanup.addView(targetCleanButton, topParams(dp(4)));
        LinearLayout localCleanup = card("清理本机占用");
        addText(localCleanup, "只处理控制手机上本应用的缓存、日志和已完成恢复资料；未完成/异常恢复事务及 ADB 授权密钥会保留。", 12, MUTED, false, dp(6), 0);
        Button openCleanup = button("打开「清理本机占用」"); openCleanup.setOnClickListener(v -> startActivityForResult(new Intent(this, CleanupActivity.class), REQUEST_LOCAL_CLEANUP));
        localCleanup.addView(openCleanup, topParams(dp(6)));
        addFriendsFooter();

        int restored = state == null ? 0 : state.getInt("currentStep", 0);
        showStep(Math.max(0, Math.min(STEP_NAMES.length - 1, restored)));
        renderSelection();
    }

    private void addFriendsFooter() {
        LinearLayout heading = new LinearLayout(this); heading.setOrientation(LinearLayout.HORIZONTAL); heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hp = topParams(dp(22)); hp.height = dp(24); page.addView(heading, hp);
        View left = new View(this); left.setBackgroundColor(Color.rgb(223,231,231));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(1), 1f); lp.rightMargin = dp(10); heading.addView(left, lp);
        TextView label = text("友情链接", 12, MUTED, false); label.setGravity(Gravity.CENTER);
        label.setClickable(true); label.setFocusable(true); label.setOnClickListener(v -> friendTitleTapped());
        heading.addView(label, params(-2, -1));
        View right = new View(this); right.setBackgroundColor(Color.rgb(223,231,231));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(1), 1f); rp.leftMargin = dp(10); heading.addView(right, rp);
        LinearLayout links = new LinearLayout(this); links.setOrientation(LinearLayout.HORIZONTAL); links.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = weight(1); bp.rightMargin = dp(8);
        links.addView(friendLink("Lino 笔记", BLOG_URL, R.drawable.lino_logo), bp);
        links.addView(friendLink("GitHub 主页", GITHUB_URL, R.drawable.github_invertocat), weight(1));
        LinearLayout.LayoutParams linksParams = params(-1, -2); linksParams.topMargin = dp(4); page.addView(links, linksParams);
    }

    private void friendTitleTapped() {
        long now = System.currentTimeMillis();
        if (now - lastFriendTitleTap > 1500) friendTitleTaps = 0;
        lastFriendTitleTap = now;
        if (++friendTitleTaps >= 5) {
            friendTitleTaps = 0;
            if (exportRuntimeLogButton != null) exportRuntimeLogButton.setVisibility(View.VISIBLE);
        }
    }

    private LinearLayout friendLink(String label, String address, int iconRes) {
        LinearLayout link = new LinearLayout(this); link.setOrientation(LinearLayout.HORIZONTAL);
        link.setGravity(Gravity.CENTER); link.setMinimumHeight(dp(44));
        link.setPadding(dp(8), dp(8), dp(8), dp(8));
        link.setBackground(roundRect(CARD, Color.rgb(223,231,231), dp(9)));
        ImageView icon = new ImageView(this); icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = params(dp(24), dp(24)); iconParams.rightMargin = dp(6);
        link.addView(icon, iconParams);
        TextView title = text(label, 13, TEAL, true); title.setGravity(Gravity.CENTER);
        link.addView(title, params(-2, -2));
        link.setClickable(true); link.setFocusable(true); link.setOnClickListener(v -> openUrl(address));
        link.setContentDescription(label + "，打开链接"); return link;
    }

    private void addStepHeading(String heading, String detail) {
        page.addView(text(heading, 20, INK, true), params(-1, -2));
        addText(page, detail, 13, MUTED, false, dp(4), dp(2));
    }

    private void buildTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews(); stepTabs.clear();
        for (int i = 0; i < STEP_NAMES.length; i++) {
            if (i == 4 && !advancedVisible()) continue;
            final int index = i;
            TextView tab = text(STEP_NAMES[i], 12, MUTED, true); tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(13), dp(9), dp(13), dp(9)); tab.setMinHeight(dp(40));
            tab.setOnClickListener(v -> showStep(index));
            LinearLayout.LayoutParams p = params(-2, -2); p.rightMargin = dp(6); tabStrip.addView(tab, p);
            stepTabs.add(tab);
        }
        updateStepChrome();
    }

    private boolean advancedVisible() { return advancedOn || (waitingResume != null && !"installed".equals(waitingResume.state())); }

    private void showStep(int index) {
        if (index < 0 || index >= STEP_NAMES.length) return;
        if (index == 4 && !advancedVisible()) index = 0;
        aboutOpen = false;
        if (tabScroller != null) tabScroller.setVisibility(View.VISIBLE);
        if (stepLine != null) stepLine.setVisibility(View.VISIBLE);
        if (stepNav != null) stepNav.setVisibility(View.VISIBLE);
        if (aboutButton != null) { aboutButton.setText("关于"); aboutButton.setContentDescription("打开关于与使用说明"); }
        currentStep = index;
        if (stepHost != null) {
            stepHost.removeAllViews(); page = stepPages.get(index); stepHost.addView(page, params(-1, -2));
            if (stepScroll != null) stepScroll.post(() -> stepScroll.scrollTo(0, 0));
        }
        updateStepChrome();
        renderSelection();
    }

    private void toggleAboutPage() {
        if (aboutOpen) { showStep(returnStep); return; }
        returnStep = currentStep;
        aboutOpen = true;
        if (stepHost != null) {
            stepHost.removeAllViews(); page = aboutPage; stepHost.addView(aboutPage, params(-1, -2));
            if (stepScroll != null) stepScroll.post(() -> stepScroll.scrollTo(0, 0));
        }
        if (tabScroller != null) tabScroller.setVisibility(View.GONE);
        if (stepLine != null) stepLine.setVisibility(View.GONE);
        if (stepNav != null) stepNav.setVisibility(View.GONE);
        if (aboutButton != null) { aboutButton.setText("返回"); aboutButton.setContentDescription("返回步骤页面"); }
    }

    private void previousStep() {
        if (currentStep == 5 && !advancedVisible()) showStep(3);
        else if (currentStep > 0) showStep(currentStep - 1);
    }

    private void nextStep() {
        if (currentStep == 3 && !advancedVisible()) showStep(5);
        else if (currentStep == 4) showStep(5);
        else if (currentStep < STEP_NAMES.length - 1) showStep(currentStep + 1);
    }

    private void updateStepChrome() {
        if (stepLine != null) {
            int visible = advancedVisible() ? STEP_NAMES.length : STEP_NAMES.length - 1;
            int ordinal = currentStep > 4 && !advancedVisible() ? currentStep : currentStep + 1;
            stepLine.setText("页面 " + ordinal + " / " + visible + " · " + STEP_NAMES[currentStep]);
        }
        int tabIndex = 0;
        for (int i = 0; i < STEP_NAMES.length; i++) {
            if (i == 4 && !advancedVisible()) continue;
            if (tabIndex >= stepTabs.size()) break;
            TextView tab = stepTabs.get(tabIndex++);
            boolean selected = currentStep == i;
            tab.setEnabled(!busy);
            tab.setTextColor(selected ? Color.WHITE : (i == 4 ? RED : MUTED));
            tab.setBackground(roundRect(selected ? TEAL : CARD, selected ? TEAL : Color.rgb(223,231,231), dp(18)));
        }
        if (aboutButton != null) aboutButton.setEnabled(!busy);
        if (previousStepButton != null) previousStepButton.setEnabled(currentStep > 0 && !busy);
        if (nextStepButton != null) {
            nextStepButton.setEnabled(currentStep < STEP_NAMES.length - 1 && !busy);
            nextStepButton.setText(currentStep == 3 && !advancedVisible() ? "跳过高级功能 →" : currentStep == 4 || currentStep == 5 ? "下一页 →" : "下一步 →");
        }
    }

    private void titleTapped() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTitleTap > 1500) titleTaps = 0;
        lastTitleTap = now; titleTaps++;
        int left = 5 - titleTaps;
        if (left > 0) {
            if (titleTaps >= 3) showTapToast("再点 " + left + " 次" + (advancedOn ? "关闭" : "开启") + "高级功能");
            return;
        }
        titleTaps = 0; advancedOn = !advancedOn; uiPrefs.edit().putBoolean("advanced", advancedOn).apply();
        showTapToast(advancedOn ? "已开启高级功能" : waitingResume != null ? "高级功能已关闭；待恢复事务仍保留入口" : "已关闭高级功能");
        buildTabs();
        if (!advancedVisible() && currentStep == 4) {
            if (aboutOpen) { currentStep = 3; returnStep = 3; updateStepChrome(); }
            else showStep(3);
        }
    }

    private void showTapToast(String message) {
        if (tapToast != null) tapToast.cancel();
        tapToast = Toast.makeText(this, message, Toast.LENGTH_SHORT); tapToast.show();
    }

    private GradientDrawable roundRect(int fill, int stroke, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(radiusPx);
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void refreshUsbList() {
        if (usbManager == null) { setStatus("系统未提供 USB Host 服务；控制手机可能不支持 OTG。"); return; }
        usbCandidates.clear(); usbCandidates.addAll(UsbAdbTransport.list(usbManager));
        List<String> labels = new ArrayList<>();
        for (UsbAdbTransport.Candidate c : usbCandidates) labels.add(c.label);
        if (labels.isEmpty()) labels.add("未发现 USB ADB 设备");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        if (usbCandidates.isEmpty()) setStatus("未发现 USB ADB 接口。请确认目标已启动 Android、开启 USB 调试，并使用支持 OTG 的线材。");
        else setStatus("发现 " + usbCandidates.size() + " 个 USB ADB 设备；请选择目标并连接。");
        updateButtons();
    }

    private void requestOrConnect() {
        if (busy) return;
        int pos = deviceSpinner == null ? -1 : deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= usbCandidates.size()) { refreshUsbList(); return; }
        UsbAdbTransport.Candidate candidate = usbCandidates.get(pos);
        UsbDevice device = usbManager.getDeviceList().get(candidate.name);
        if (device == null) { setStatus("设备已断开；请重新插线并扫描。"); refreshUsbList(); return; }
        if (!usbManager.hasPermission(device)) {
            setStatus("请在系统弹窗中允许控制器访问 USB 设备。");
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()).putExtra(EXTRA_DEVICE_NAME, candidate.name);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 3101, intent, flags));
            return;
        }
        connectUsbNow(candidate.name);
    }

    private void connectUsbNow(String deviceName) {
        if (busy) return;
        UsbAdbTransport.Candidate candidate = null;
        for (UsbAdbTransport.Candidate c : usbCandidates) if (c.name.equals(deviceName)) { candidate = c; break; }
        if (candidate == null) { refreshUsbList(); setStatus("目标 USB 设备不在列表中，请重新扫描。"); return; }
        final String label = candidate.label;
        final UsbDevice device = candidate.dev;
        AppRuntimeLog.record("ADB", "connect requested; USB handshake worker starting");
        setBusy(true, "正在通过 USB ADB 握手。若此前未授权，请在目标设备确认本应用显示的 RSA 指纹……");
        worker.execute(() -> {
            AdbConnection next = null;
            try {
                AppRuntimeLog.record("ADB", "worker entered; clearing previous session state");
                AdbConnection previous = adb;
                if (previous != null) previous.close();
                stagedConnection = null; stagedZipPath = ""; stagedName = ""; stagedSha = ""; stageId = "";
                stagedManifest=null;planSession=null;webStatus=null;waitingResume=null;resumeValidated=false;resumeValidationError="";
                remoteJobPending=false;remoteJobKind="";remoteJobName="";adb = null; target = null;
                helperStageConnection=null;targetStageInventory=null;targetStageInventoryConnection=null;
                AppRuntimeLog.record("ADB", "loading or creating host authorization key");
                AdbKey key = AdbKey.loadOrCreate(new File(getFilesDir(), "adbkey"));
                AppRuntimeLog.record("ADB", "opening USB ADB transport");
                UsbAdbTransport transport = UsbAdbTransport.open(usbManager, device);
                AppRuntimeLog.record("ADB", "USB transport open; starting ADB protocol handshake");
                next = AdbConnection.connect(transport, "USB · " + label, key,
                        "mu300-otg@" + safeKeyName(Build.MODEL), fp -> setStatus("等待目标设备允许 ADB 主机密钥。指纹：" + fp + "。U30 Air 无屏时需提前授权该密钥。"));
                AppRuntimeLog.record("ADB", "ADB protocol handshake complete; probing target");
                adb = next;
                DeviceInfo info = probeTarget(next, "USB · " + label);
                target = info;
                WebInstallerClient client=new WebInstallerClient(this,next);
                client.stage();helperStageConnection=next;
                JSONObject helper=client.status(true);
                webStatus=helper;
                if(info.ready()) {
                    try {
                        String[] identity=MakeRoomPreflight.readSerialCid(next);
                        MakeRoomTransaction.Record rec=new MakeRoomTransaction(this,next).findWaiting(identity[0],identity[1]);
                        if(rec!=null) {
                            waitingResume=rec;
                            verifiedZip=rec.zip;verifiedName=rec.zipName();verifiedSha=rec.zipSha();
                            try {
                                MakeRoomPreflight.Result p=MakeRoomPreflight.inspectForResume(next,helper);
                                new MakeRoomTransaction(this,next).validateResume(rec,p,helper);
                                resumeValidated=true;
                            } catch(Exception re) { resumeValidationError=safeMessage(re); }
                        }
                    } catch(Exception re) { resumeValidationError=safeMessage(re); }
                }
                reconcileRemoteJob(helper);
                final String facts = info.brief();
                AppRuntimeLog.record("ADB", "target probe and helper status complete");
                appendLog("USB ADB 已连接并完成目标检查。" + facts.replace('\n', '；'));
                String helperMessage=waitingResume==null?"官方 helper 状态与 ADB 目标已连接。":resumeValidated?"发现匹配设备且事务/备份校验通过，可按原 ZIP 续接。":"发现待恢复事务，但校验未通过："+resumeValidationError;
                String connectionMessage = info.ready() ? helperMessage : info.root
                        ? "目标 root 已授权，但 Magisk versionCode 未确认达到 26000；dry-run 与 INSTALL 暂不可用。请查看设备详情中的版本码。"
                        : "目标已连接但未授予 root；传输、目标复核和只读 dry-run 均不可用。";
                setBusy(false, connectionMessage);
                if (alive) main.post(() -> {
                    if (deviceView != null) deviceView.setText(facts);
                    updateHelperView(); renderSelection(); buildTabs();
                    showStep(waitingResume != null ? 4 : info.ready() ? 1 : 0);
                });
            } catch (Exception e) {
                AppRuntimeLog.recordThrowable("ERROR", "USB ADB connect/check failed", e);
                if (next != null) next.close();
                adb = null; target = null;helperStageConnection=null;targetStageInventory=null;targetStageInventoryConnection=null;
                String err = safeMessage(e);
                appendLog("USB ADB 连接/检查失败：" + err);
                setBusy(false, "连接或目标检查失败：" + err);
                if (alive) main.post(() -> { if (deviceView != null) deviceView.setText("未完成连接。检查目标 USB 调试、系统 USB 授权与 Magisk root 授权，再重试。"); updateButtons(); });
            }
        });
    }

    private void reconcileRemoteJob(JSONObject status) {
        JSONObject job=status==null?null:status.optJSONObject("job");
        if(job==null)return;
        String state=job.optString("state",""),kind=job.optString("kind",""),name=job.optString("arg","");
        remoteJobKind=kind;remoteJobName=name;
        int rc=job.optInt("rc",-999);
        if("install".equals(kind)) {
            String expected=prefs.getString("install_name","");
            if("running".equals(state)) {
                remoteJobPending=true;installWasAttempted=true;prefs.edit().putString("install_state","pending").apply();
            } else if("done".equals(state)&&rc==0&&!name.isEmpty()&&(expected.isEmpty()||expected.equals(name))) {
                remoteJobPending=false;installWasAttempted=false;prefs.edit().putString("install_state","success").putString("install_name",name).apply();
                appendLog("上游 helper 确认目标端安装任务已成功完成（rc=0）。");
            } else if("done".equals(state)||"interrupted".equals(state)||"unknown".equals(state)) {
                remoteJobPending=false;installWasAttempted=true;prefs.edit().putString("install_state","unknown").apply();
                appendLog("目标端安装已有终态但未获成功确认；已锁定新安装，必须先人工检查目标。");
            }
        } else if(RemoteJobState.blocksTransfer(kind,state,rc)) {
            remoteJobPending=true;
        }
    }

    private DeviceInfo probeTarget(AdbConnection c, String endpoint) throws Exception {
        DeviceInfo d = new DeviceInfo(); d.endpoint = endpoint;
        String propCmd = "echo MODEL=$(getprop ro.product.model); echo DEVICE=$(getprop ro.product.device); "
                + "echo ANDROID=$(getprop ro.build.version.release); echo SLOT=$(getprop ro.boot.slot_suffix); "
                + "echo BOOT=$(getprop sys.boot_completed); echo VERIFIED=$(getprop ro.boot.verifiedbootstate); "
                + "echo FLASHLOCK=$(getprop ro.boot.flash.locked); echo DEVICESTATE=$(getprop ro.boot.vbmeta.device_state)";
        String props = c.shellText(propCmd);
        for (String line : props.split("\\r?\\n")) {
            int eq = line.indexOf('='); if (eq <= 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
            if ("MODEL".equals(k)) d.model = v;
            else if ("DEVICE".equals(k)) d.device = v;
            else if ("ANDROID".equals(k)) d.android = v;
            else if ("SLOT".equals(k)) d.slot = v.isEmpty() ? "unknown" : v;
            else if ("BOOT".equals(k)) d.bootCompleted = v;
            else if ("VERIFIED".equals(k)) d.lock = v;
            else if ("FLASHLOCK".equals(k) && !v.isEmpty()) d.lock += "; flash_locked=" + v;
            else if ("DEVICESTATE".equals(k) && !v.isEmpty()) d.lock += "; vbmeta=" + v;
        }
        List<String> rootLines = new ArrayList<>();
        int rootRc = c.shell("su -c 'id -u' 2>&1", rootLines::add, 120000);
        d.root = rootRc == 0 && containsExactLine(rootLines, "0");
        if (d.root) {
            List<String> magiskLines = new ArrayList<>();
            c.shell("su -c 'magisk -v' 2>&1", magiskLines::add, 30000);
            for (String line : magiskLines) if (!line.trim().isEmpty() && !line.contains("__OWRT_ADB_RC=")) {
                d.magisk = redact(line.trim());
                break;
            }
            List<String> versionCodeLines = new ArrayList<>();
            c.shell("su -c 'magisk -V' 2>&1", versionCodeLines::add, 30000);
            int versionCode = MagiskVersion.parseVersionCode(versionCodeLines);
            d.magiskOk = MagiskVersion.supportsInstaller(versionCode);
            if (versionCode >= 0) d.magisk += "（versionCode " + versionCode + "）";
            else d.magisk += "（versionCode 未读取）";
        } else d.magisk = "未授予 / 不可用";
        String hint = (d.model + " " + d.device).toLowerCase(Locale.ROOT);
        d.supportedHint = hint.contains("u30 air") || hint.contains("u30air") || hint.contains("mu300")
                || hint.contains("f50") || hint.equals("e5") || hint.contains("e5");
        return d;
    }

    private static boolean containsExactLine(List<String> lines, String value) {
        for (String line : lines) if (value.equals(line.trim())) return true;
        return false;
    }

    private void updateKernelOptions() {
        if (kernelSpinner == null || osSpinner == null) return;
        int old = kernelSpinner.getSelectedItemPosition();
        boolean ubuntu26 = osSpinner.getSelectedItemPosition() == 3;
        String[] values = ubuntu26 ? new String[]{"6.18 LTS", "7.2", "7.3（较新，测试较少）"}
                : new String[]{"5.4（厂商内核）", "6.18 LTS", "7.2", "7.3（较新，测试较少）"};
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        initializingSpinners = true;
        kernelSpinner.setAdapter(a);
        kernelSpinner.setSelection(ubuntu26 ? Math.max(0, Math.min(old - 1, 2)) : Math.max(0, Math.min(old, 3)));
        initializingSpinners = false;
    }

    private String systemSlug() {
        if (osSpinner == null) return "openwrt";
        switch (osSpinner.getSelectedItemPosition()) {
            case 1: return "openwrt-luci";
            case 2: return "ubuntu-24.04";
            case 3: return "ubuntu-26.04";
            default: return "openwrt";
        }
    }
    private String systemLabel() {
        if (osSpinner == null) return "OpenWrt";
        Object x = osSpinner.getSelectedItem(); return x == null ? "OpenWrt" : x.toString();
    }
    private String kernelValue() {
        Object x = kernelSpinner == null ? null : kernelSpinner.getSelectedItem();
        String s = x == null ? "5.4" : x.toString();
        if (s.startsWith("5.4")) return "5.4";
        if (s.startsWith("6.18")) return "6.18";
        if (s.startsWith("7.2")) return "7.2";
        return "7.3";
    }
    private String selectedAssetName() {
        return latest == null ? "" : "mu300-magisk-" + latest.tag + "-" + systemSlug() + "-k" + kernelValue() + ".zip";
    }
    private String installAssetName() { return waitingResume==null?selectedAssetName():waitingResume.zipName(); }
    private Asset selectedAsset() { return latest == null ? null : latest.assets.get(selectedAssetName()); }

    private void renderSelection() {
        if (releaseView == null) return;
        String name = installAssetName(); Asset a = selectedAsset();
        if (latest == null) releaseView.setText("尚未读取官方发布。");
        else if (a == null) releaseView.setText("官方发布 " + latest.tag + " 中没有此系统/内核组合；请换一个选项。");
        else releaseView.setText("最新发布：" + latest.tag + "\n文件：" + a.name + "\n大小：" + formatBytes(a.size));
        if(waitingResume!=null) releaseView.setText("恢复事务绑定的原始官方 ZIP："+waitingResume.zipName()+"\nSHA-256："+waitingResume.zipSha()+"\n不可替换为其他发布或内核版本。");
        if (fileView != null) {
            if (verifiedZip != null && verifiedZip.exists() && name.equals(verifiedName))
                fileView.setText("控制手机已下载并校验：" + verifiedName + "\nSHA-256：" + verifiedSha);
            else if (a == null) fileView.setText("所选组合没有官方 ZIP。");
            else fileView.setText("尚未下载并校验当前选择的 ZIP。");
        }
        if (stageView != null) {
            if (!stagedZipPath.isEmpty() && stagedConnection == adb && installAssetName().equals(stagedName))
                stageView.setText("作者 helper 已接收并复核 SHA-256：" + stagedName + "\n目标 root 私有暂存：" + stagedZipPath + "\n" + stagedSha);
            else if (!stagedZipPath.isEmpty()) stageView.setText("暂存 ZIP 属于另一条 USB ADB 会话；请对当前目标重新推送并复核。");
            else stageView.setText("目标端尚未收到当前所选 ZIP。");
        }
        updateButtons();
    }

    private void invalidateSelectedFile() {
        if(waitingResume!=null)return;
        planSession=null;
        if (verifiedZip != null && !selectedAssetName().equals(verifiedName)) {
            verifiedZip = null; verifiedName = ""; verifiedSha = "";
        }
    }

    private void refreshRelease() {
        if (busy) return;
        setBusy(true, "正在从 GitHub 官方 API 读取最新 Magisk ZIP 发布与校验清单……");
        worker.execute(() -> {
            try {
                Release r = fetchLatestRelease(); latest = r;
                appendLog("已读取官方发布：" + r.tag + "；可用 Magisk ZIP：" + r.assets.size());
                setBusy(false, "官方发布已读取：" + r.tag + "。请选择系统与内核。");
                if (alive) main.post(this::renderSelection);
            } catch (Exception e) {
                String err = safeMessage(e); appendLog("读取官方发布失败：" + err);
                setBusy(false, "读取官方发布失败：" + err);
                if (alive) main.post(this::renderSelection);
            }
        });
    }

    private Release fetchLatestRelease() throws Exception {
        JSONObject json = new JSONObject(readUrl(RELEASE_API, 3 * 1024 * 1024));
        String tag = json.getString("tag_name");
        if (!tag.matches("v[0-9A-Za-z._-]{1,40}")) throw new IllegalStateException("官方版本标签格式异常");
        JSONArray arr = json.getJSONArray("assets");
        java.util.Map<String, Asset> assets = new java.util.HashMap<>();
        String sums = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i); String name = a.getString("name");
            String url = a.getString("browser_download_url"); long size = a.optLong("size", -1);
            if (!isSafeAssetName(name)) continue;
            requireOfficialReleaseUrl(url);
            assets.put(name, new Asset(name, url, size));
            if ("SHA256SUMS-magisk".equals(name)) sums = url;
        }
        if (sums == null) throw new IllegalStateException("官方发布缺少 SHA256SUMS-magisk");
        return new Release(tag, sums, assets);
    }

    private boolean isSafeAssetName(String name) {
        return name.matches("[A-Za-z0-9._-]{1,160}") && !name.contains("..") && !name.startsWith(".");
    }

    private void downloadSelected() {
        if (busy || latest == null) return;
        Asset asset = selectedAsset();
        if (asset == null) { setStatus("所选系统和内核组合没有官方 ZIP。"); return; }
        if (asset.size <= 0 || asset.size > MAX_ZIP_BYTES) { setStatus("官方文件大小不在允许范围（最多 300 MiB）；停止下载。"); return; }
        setBusy(true, "正在下载到控制手机并校验 GitHub 发布 SHA-256；此步骤尚未连接/写入目标。");
        progress.setIndeterminate(false); progress.setProgress(0);
        final String expectedName = asset.name;
        worker.execute(() -> {
            File part = new File(getCacheDir(), asset.name + ".part");
            File dest = new File(getCacheDir(), asset.name);
            try {
                String manifest = readUrl(latest.checksumUrl, 1024 * 1024);
                String expectedSha = checksumFor(manifest, asset.name);
                if (expectedSha == null) throw new IllegalStateException("SHA256SUMS-magisk 中找不到所选文件的唯一校验值");
                if (part.exists()) part.delete();
                long bytes = downloadToFile(asset.url, part, asset.size, (done, total) -> {
                    if (alive) main.post(() -> { if (progress != null && total > 0) progress.setProgress((int)Math.min(100, done * 100 / total)); });
                });
                if (bytes != asset.size) throw new IllegalStateException("下载字节数与官方发布大小不符");
                String actual = sha256(part);
                if (!expectedSha.equalsIgnoreCase(actual)) throw new IllegalStateException("SHA-256 不匹配；拒绝推送到目标设备");
                if (dest.exists() && !dest.delete()) throw new IllegalStateException("无法替换旧的缓存文件");
                if (!part.renameTo(dest)) throw new IllegalStateException("校验通过但无法保存下载文件");
                verifiedZip = dest; verifiedName = expectedName; verifiedSha = actual;
                appendLog("控制手机下载和 SHA-256 校验通过：" + expectedName + "（" + formatBytes(bytes) + "）。");
                setBusy(false, "ZIP 已在控制手机校验；还没有推送或安装。");
                if (alive) main.post(() -> { renderSelection(); showStep(2); });
            } catch (Exception e) {
                if (part.exists()) part.delete(); String err = safeMessage(e);
                appendLog("下载/校验失败：" + err); setBusy(false, "下载/校验失败：" + err);
                if (alive) main.post(this::renderSelection);
            }
        });
    }

    private void pushToTarget() {
        if (busy) return;
        if (adb == null || adb.isClosed() || target == null || !target.root) { setStatus("请先连接目标并确认已授予 root；Magisk 26+ 是后续 dry-run 与安装的要求。"); return; }
        if(remoteJobPending){resumeRemoteJob();return;}
        if(waitingResume!=null&&!resumeValidated){setStatus("待恢复事务尚未通过设备、GPT、marker 与备份校验："+resumeValidationError);return;}
        if (verifiedZip == null || !verifiedZip.exists() || !verifiedName.equals(installAssetName())) { setStatus("请先下载并通过 SHA-256 校验当前所选 ZIP；恢复事务必须使用本机保存的原始 ZIP。"); return; }
        if (installWasAttempted) { setStatus("本会话已尝试安装；为避免重复写入，请不要再次启动安装。"); return; }
        final AdbConnection conn = adb;
        final File zip = verifiedZip;
        final String name = verifiedName, hash = verifiedSha;
        final MakeRoomTransaction.Record resumeRecord=waitingResume;
        setBusy(true, "正在复核 helper 状态与 /data 空间，然后上传 ZIP 并由目标端复核 SHA-256……");
        progress.setMax(100); progress.setIndeterminate(false); progress.setProgress(0);
        worker.execute(() -> {
            try {
                ensureHelperStaged(conn);
                WebInstallerClient client=new WebInstallerClient(this,conn);
                JSONObject status=client.status(true);
                JSONObject job=status.optJSONObject("job");
                if(job!=null&&"running".equals(job.optString("state","")))throw new IllegalStateException("目标端已有任务正在运行；请先续接同一个任务，不要上传或启动第二个任务。");
                long available=status.optLong("data_free",-1);
                long required = zip.length() + RESERVED_DATA_BYTES;
                if (available < 0 || available < required) throw new IllegalStateException("目标 helper 工作目录所在 /data 空间不足：剩余 " + formatBytes(available)
                        + "，本次 ZIP 加官方保留空间至少需要 " + formatBytes(required) + "；尚未上传，也未安装。");
                appendLog("目标 /data 空间预检通过：可用 " + formatBytes(available) + "，保留至少 512 MiB 给官方安装脚本。");
                if(resumeRecord!=null){
                    MakeRoomPreflight.Result rp=MakeRoomPreflight.inspectForResume(conn,status);
                    new MakeRoomTransaction(this,conn).validateResume(resumeRecord,rp,status);
                }
                final long totalBytes=Math.max(1L,zip.length());
                final int[] lastPercent={-1};
                WebInstallerClient.ZipManifest manifest=client.take(zip,name,hash,new AdbConnection.Progress(){
                    @Override public void bytes(long done){
                        int pct=(int)Math.min(100L,done*100L/totalBytes);
                        if(pct==lastPercent[0])return;
                        lastPercent[0]=pct;
                        if(pct==0||pct==25||pct==50||pct==75||pct==100)AppRuntimeLog.record("TRANSFER","official ZIP upload "+pct+"%");
                        if(alive)main.post(()->{
                            if(!alive||progress==null)return;
                            progress.setIndeterminate(false);progress.setProgress(pct);
                            if(statusView!=null)statusView.setText("正在上传 ZIP 并准备目标端 SHA-256 复核："+pct+"%（"+formatBytes(Math.min(done,totalBytes))+" / "+formatBytes(totalBytes)+"）");
                        });
                    }
                    @Override public boolean cancelled(){return !alive;}
                },()->{
                    AppRuntimeLog.record("TRANSFER","official ZIP upload complete; target SHA-256 and manifest verification starting");
                    if(alive)main.post(()->{
                        if(!alive)return;
                        if(progress!=null)progress.setIndeterminate(true);
                        if(statusView!=null)statusView.setText("ZIP 已上传，正在由目标端复核 SHA-256 和 manifest……");
                    });
                });
                if(resumeRecord!=null&&!resumeRecord.system().equals(manifest.system))throw new IllegalStateException("helper 返回的恢复 ZIP 系统与事务不符");
                stagedZipPath=WebInstallerClient.STAGE+"/zips/"+name;stagedName=name;stagedSha=hash;stagedManifest=manifest;stagedConnection=conn;webStatus=status;
                appendLog("作者 helper 已接收 ZIP 并验证其 manifest 与 SHA-256："+name+"。");
                setBusy(false, "ZIP 已进入官方 helper 暂存；尚未安装。下一步必须运行 dry-run 计划。");
                if (alive) main.post(() -> { renderSelection(); showStep(3); });
            } catch (Exception e) {
                String err = safeMessage(e); appendLog("OTG 推送/目标校验失败：" + err);
                setBusy(false, "传输或目标端校验失败：" + err + "。未触发安装。");
                if (alive) main.post(this::updateButtons);
            }
        });
    }

    private void beginPlanQuestionnaire() {
        if(remoteJobPending){resumeRemoteJob();return;}
        if(busy||adb==null||adb.isClosed()||target==null||!target.ready()) { setStatus("请先连接并授权目标设备，确认 Magisk 版本为 26+。");return; }
        if(waitingResume!=null&&!resumeValidated){setStatus("恢复事务未通过校验："+resumeValidationError);return;}
        if(stagedConnection!=adb||stagedManifest==null||!stagedName.equals(installAssetName())||!stagedSha.equals(verifiedSha)) { setStatus("请先推送并由作者 helper 校验官方 ZIP。");return; }
        final boolean regionOverwrite=waitingResume!=null;
        LinearLayout box=column();box.setPadding(dp(18),dp(4),dp(18),0);
        CheckBox linuxDefault=new CheckBox(this);linuxDefault.setText("默认启动 Linux（Android 仍按官方 boot-fallback 设置）");linuxDefault.setChecked(true);box.addView(linuxDefault);
        addText(box,"连续启动失败次数",13,INK,true,dp(8),0);
        Spinner attempts=spinner(new String[]{"1","2","3","4","5","6"});attempts.setSelection(4);box.addView(attempts,topParams(dp(2)));
        CheckBox hotspot=new CheckBox(this);hotspot.setText("导入 Android 热点设置");hotspot.setChecked(true);box.addView(hotspot,topParams(dp(6)));
        CheckBox gpu=new CheckBox(this);gpu.setText("安装 Mali GPU / OpenCL 支持文件");gpu.setChecked(true);box.addView(gpu,topParams(dp(2)));
        EditText password=new EditText(this);password.setSingleLine(true);password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("可选：新 Linux 密码（留空使用官方镜像默认值）");box.addView(password,topParams(dp(8)));
        addText(box,"参数将以 root-only answers 文件传给官方 Magisk ZIP。密码只在控制端当前进程内暂存、仅经 helper 的受限 stdin 传输，不写入日志/事务文件。Android userdata 已完成恢复时，恢复任务会额外带上官方 MU300_REGION_OVERWRITE=yes，仅覆盖经 marker 验证的新 Linux region。",12,MUTED,false,dp(8),0);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(regionOverwrite?"恢复事务安装问卷":"官方安装参数问卷")
                .setView(box).setNegativeButton("取消",(d,w)->{}).setPositiveButton("运行只读计划",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String pass=password.getText().toString();
            if(!pass.isEmpty()&&(pass.length()<6||pass.length()>128||pass.contains("\n")||pass.contains("\r"))){password.setError("密码需为 6–128 个字符且不能换行");return;}
            int selected=attempts.getSelectedItemPosition()+1;
            String answers=InstallerAnswers.forSingleSystem(linuxDefault.isChecked(),selected,hotspot.isChecked(),gpu.isChecked(),regionOverwrite);
            dialog.dismiss();runDryRunPlan(answers,pass,selected,linuxDefault.isChecked(),hotspot.isChecked(),gpu.isChecked(),regionOverwrite);
        }));
        dialog.show();
    }

    private void runDryRunPlan(String answers,String password,int attempts,boolean linuxDefault,boolean hotspot,boolean gpu,boolean regionOverwrite) {
        if(busy||adb==null||adb.isClosed())return;
        final AdbConnection conn=adb;final String name=stagedName,hash=stagedSha;final WebInstallerClient.ZipManifest manifest=stagedManifest;
        final MakeRoomTransaction.Record resume=waitingResume;
        setBusy(true,"正在重新读取目标状态并运行官方 dry-run；此阶段不写 Linux 分区、启动槽或 misc。");
        if(progress!=null)progress.setIndeterminate(true);
        worker.execute(()->{
            int rc=-1;String failure=null;Map<String,String> values=new HashMap<>();
            try{
                ensureHelperStaged(conn);
                WebInstallerClient client=new WebInstallerClient(this,conn);JSONObject status=client.status(true);
                if(resume!=null){MakeRoomPreflight.Result p=MakeRoomPreflight.inspectForResume(conn,status);new MakeRoomTransaction(this,conn).validateResume(resume,p,status);}
                client.setAnswers(answers,password);client.start("plan",name);
                rc=client.follow((line,event)->onJobLine(line,event,values));
                if(rc!=3)throw new IllegalStateException("官方 dry-run 未以预期退出码 3 完成（实际 "+rc+"）；拒绝显示安装确认。");
                validatePlan(values,manifest,status,attempts,linuxDefault,hotspot,gpu,regionOverwrite);
                planSession=new PlanSession(name,hash,answers,password,manifest,new HashMap<>(values),regionOverwrite,attempts,linuxDefault,hotspot,gpu,status);
                webStatus=status;remoteJobPending=false;remoteJobKind="";remoteJobName="";
            }catch(Exception e){failure=safeMessage(e);planSession=null;}
            final String error=failure;final int result=rc;
            setBusy(false,error==null?"官方 dry-run 已完成且关键字段与所选 ZIP/设备状态一致。请查看计划并二次确认 INSTALL。":"dry-run 未通过安全验证："+error);
            if(error==null)appendLog("dry-run 计划校验通过："+planSummary(planSession).replace('\n','；'));
            if(alive)main.post(()->{if(error!=null)Toast.makeText(this,"计划已停止，未开始安装。",Toast.LENGTH_LONG).show();updateHelperView();updateButtons();});
        });
    }

    private void validatePlan(Map<String,String> p,WebInstallerClient.ZipManifest manifest,JSONObject status,int attempts,boolean linuxDefault,boolean hotspot,boolean gpu,boolean regionOverwrite) {
        if(!InstallerProtocol.completePlan(p))throw new IllegalStateException("官方计划缺少一个或多个 machine plan 字段");
        JSONObject region=status.optJSONObject("region");if(region==null)throw new IllegalStateException("状态缺少 region 几何");
        if(!manifest.tag.equals(p.get("tag"))||!manifest.system.equals(p.get("system"))||!manifest.os.equals(p.get("os"))
                ||!manifest.ubuntu.equals(p.get("ubuntu"))||!manifest.kernel.equals(p.get("kernel")))throw new IllegalStateException("计划的版本/系统/Ubuntu 版本/内核与所选 ZIP manifest 不一致");
        if(!status.optString("device","").equals(p.get("device"))||!"internal".equals(p.get("storage")))throw new IllegalStateException("计划设备或存储目标不符合所选目标");
        if(!Long.toString(region.optLong("off",-1)).equals(p.get("offset"))||!Long.toString(region.optLong("size",-1)).equals(p.get("size")))throw new IllegalStateException("计划 region offset/size 与 helper 只读状态不一致");
        if(!status.optString("android_slot","").equals(p.get("android_slot"))||!status.optString("linux_slot","").equals(p.get("linux_slot")))throw new IllegalStateException("计划启动槽与 helper 状态不一致");
        if(!"1".equals(p.get("dry_run"))||!"1".equals(p.get("sync_bootchain"))||!"0".equals(p.get("adb"))
                ||!InstallerProtocol.answersMatch(p,attempts,linuxDefault,hotspot,gpu))throw new IllegalStateException("计划没有完整应用本次问卷参数、bootchain 设置或不是 dry-run");
        if(regionOverwrite&&(!"1".equals(p.get("format"))||!"0".equals(p.get("update"))))throw new IllegalStateException("恢复后的新 Linux region 应为首次格式化安装，计划结果不符");
        if(!"0".equals(p.get("format"))&&!"1".equals(p.get("format")))throw new IllegalStateException("计划 format 字段无效");
        if(!"0".equals(p.get("update"))&& !"1".equals(p.get("update")))throw new IllegalStateException("计划 update 字段无效");
    }

    private String planSummary(PlanSession s) {
        if(s==null)return "尚无通过校验的 dry-run 计划。";
        Map<String,String> p=s.values;
        return "版本："+p.get("tag")+"\n系统/内核："+p.get("system")+" / "+p.get("kernel")+"\n目标："+p.get("device")+"\n目标启动槽：Android "+p.get("android_slot")+"，Linux "+p.get("linux_slot")
                +"\n内部 Linux region：offset "+p.get("offset")+"，大小 "+formatBytes(parseLong(p.get("size")))+"\n写入类型："+("1".equals(p.get("format"))?"首次格式化新 Linux 区域":"更新现有 Linux 系统")
                +"\n默认启动："+p.get("boot_os")+" / "+("1".equals(p.get("default_linux"))?"Linux":"Android")+"；失败回退次数 "+p.get("boot_attempts")
                +"\n热点导入："+p.get("hotspot")+"；GPU 支持："+p.get("gpu")+"；密码："+("given".equals(p.get("password"))?"本次自定义（不显示）":"官方/现有值："+p.get("password"))
                +(s.regionOverwrite?"\n恢复模式：事务令牌与本地备份已校验；answers 将授权覆盖新 region 中的旧 userdata 残留。":"")
                +"\n\n官方 dry-run 只读结束；确认前不执行安装写入。";
    }

    private static long parseLong(String s){try{return Long.parseLong(s);}catch(Exception e){return -1;}}

    private void onJobLine(String line,InstallerProtocol.Event event,Map<String,String> plans) {
        if("plan".equals(event.type))plans.put(event.key,event.value);
        if("step".equals(event.type))setStatus("官方安装步骤 "+event.n+"/"+event.of+"："+event.key);
        else if("progress".equals(event.type)&&event.of>0&&alive)main.post(()->{if(progress!=null){progress.setIndeterminate(false);progress.setMax(event.of);progress.setProgress(event.n);}});
        appendLog("helper："+redact(line));
    }

    private void confirmInstall() {
        if(remoteJobPending){resumeRemoteJob();return;}
        PlanSession s=planSession;
        if(busy||adb==null||adb.isClosed()||target==null||!target.ready()||installWasAttempted||s==null||stagedConnection!=adb
                ||!s.zipName.equals(stagedName)||!s.zipSha.equals(stagedSha)||!s.zipName.equals(installAssetName())||(waitingResume!=null&&!resumeValidated)) {
            setStatus("当前目标、dry-run 计划、恢复事务或 ZIP 校验状态不满足最终安装条件。");return;
        }
        LinearLayout box=column();box.setPadding(dp(18),dp(4),dp(18),0);
        final int maxConfirmHeight=Math.max(dp(160),Math.min(dp(420),(int)(getResources().getDisplayMetrics().heightPixels*0.58f)-dp(100)));
        ScrollView confirmationScroll=new ScrollView(this){
            @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
                int mode=View.MeasureSpec.getMode(heightMeasureSpec),size=View.MeasureSpec.getSize(heightMeasureSpec);
                if(mode==View.MeasureSpec.UNSPECIFIED||size>maxConfirmHeight)
                    heightMeasureSpec=View.MeasureSpec.makeMeasureSpec(maxConfirmHeight,View.MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec,heightMeasureSpec);
            }
        };
        confirmationScroll.setFillViewport(false);confirmationScroll.setVerticalScrollBarEnabled(true);
        confirmationScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        TextView msg=text("目标（USB OTG）：\n"+target.model+" / "+target.device+"，Android "+target.android+"，当前槽 "+target.slot+"\n\nZIP："+s.zipName+"\nSHA-256："+s.zipSha+"\n\n"+planSummary(s)
                +"\n\n安装由作者的官方 Magisk ZIP/helper 在目标端执行，可能写 Linux region、另一个启动槽的 boot chain 与 misc。缩容事务安装前，Android userdata 已按 ERASE 确认完成清除；不会再次缩小 userdata。断电、脚本或存储故障仍可能导致无法启动。保持 OTG 供电和连接。",14,INK,false);
        box.addView(msg,params(-1,-2));
        TextView installPrompt=text("输入大写 INSTALL 才开始写入。",13,INK,true);box.addView(installPrompt,topParams(dp(6)));
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("输入 INSTALL");box.addView(input,topParams(dp(4)));
        confirmationScroll.addView(box,params(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("最终确认：安装作者官方 ZIP").setView(confirmationScroll).setNegativeButton("取消",(d,w)->{}).setPositiveButton("开始安装",null).create();
        dialog.setOnShowListener(x->{Button ok=dialog.getButton(AlertDialog.BUTTON_POSITIVE);ok.setEnabled(false);input.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int st,int count,int after){} @Override public void onTextChanged(CharSequence s,int st,int before,int count){ok.setEnabled("INSTALL".contentEquals(s));} @Override public void afterTextChanged(android.text.Editable e){}
        });ok.setOnClickListener(v->{if("INSTALL".contentEquals(input.getText())){dialog.dismiss();runRemoteInstall();}});});dialog.show();
    }

    private void runRemoteInstall() {
        if(busy||adb==null||adb.isClosed()||installWasAttempted||planSession==null)return;
        final PlanSession session=planSession;final AdbConnection conn=adb;final MakeRoomTransaction.Record resume=waitingResume;
        installInProgress=true;setBusy(true,"正在通过官方 helper 执行已经预览并确认的安装；保持 USB/OTG 连接、供电与前台运行。");
        appendLog("用户输入 INSTALL 确认；将使用与已校验 dry-run 完全相同的 ZIP、answers 和内存密码。");
        worker.execute(()->{
            boolean started=false;int rc=-1;String failure=null;
            try{
                ensureHelperStaged(conn);
                WebInstallerClient client=new WebInstallerClient(this,conn);JSONObject status=client.status(true);
                validatePlanContext(session,status);
                if(resume!=null){MakeRoomPreflight.Result p=MakeRoomPreflight.inspectForResume(conn,status);new MakeRoomTransaction(this,conn).validateResume(resume,p,status);}
                client.setAnswers(session.answerText,session.password);
                installWasAttempted=true;remoteJobPending=true;remoteJobKind="install";remoteJobName=session.zipName;
                prefs.edit().putString("install_state","pending").putString("install_name",session.zipName).commit();
                started=true;client.start("install",session.zipName);
                rc=client.follow((line,event)->onJobLine(line,event,new HashMap<>()));
                if(rc!=0)throw new IllegalStateException("官方 helper install 返回非零退出码 "+rc);
                if(resume!=null)new MakeRoomTransaction(this,conn).markInstalled(resume);
                installWasAttempted=false;remoteJobPending=false;prefs.edit().putString("install_state","success").putString("install_name",session.zipName).commit();
            }catch(Exception e){failure=safeMessage(e);if(started){installWasAttempted=true;prefs.edit().putString("install_state","unknown").putString("install_name",session.zipName).commit();}else{remoteJobPending=false;}}
            final int result=rc;final String error=failure;final boolean wasStarted=started;
            installInProgress=false;planSession=null;
            setBusy(false,error==null?"官方 helper 确认安装完成（rc=0）。阅读输出并按官方指南手动重启；本应用不会自动重启。":wasStarted?"安装结果未知或失败；helper 任务已锁定，重新连接可 follow 同一任务。不要盲目重试。":"安装尚未开始（状态/计划复核或答案传递失败）；可以修正后重新预览。未触发安装。");
            appendLog("helper install 退出码："+result+(error==null?"":"；错误："+error));
            if(alive)main.post(()->{updateHelperView();renderSelection();});
        });
    }

    private void validatePlanContext(PlanSession s,JSONObject status) {
        validatePlan(s.values,s.manifest,status,s.attempts,s.linuxDefault,s.hotspot,s.gpu,s.regionOverwrite);
        requireReadyHelperStatus(status);
        requireSnapshotSame(s.statusSnapshot,status);
        JSONObject job=status.optJSONObject("job");
        if(job==null||!"done".equals(job.optString("state",""))||!"plan".equals(job.optString("kind",""))
                ||!s.zipName.equals(job.optString("arg",""))||job.optInt("rc",-1)!=3)
            throw new IllegalStateException("最终确认时目标端不再持有本 ZIP 成功结束的官方 dry-run 计划；请重新预览");
    }

    private static void requireReadyHelperStatus(JSONObject s) {
        if(s.optInt("v",-1)!=1||s.optInt("boot_completed",0)!=1||s.optInt("slots_ok",0)!=1
                ||s.optInt("magiskboot",0)!=1||!"magisk".equals(s.optString("root_kind","")))
            throw new IllegalStateException("dry-run 后 Android 启动、A/B、Magisk root 或 magiskboot 状态变化；请重新连接/预览");
        JSONObject misc=s.optJSONObject("misc");
        if(misc==null||misc.optInt("valid",0)!=1||misc.optString("bc","").isEmpty())
            throw new IllegalStateException("dry-run 后 misc 启动控制块无效；拒绝安装");
        if(s.optLong("disk",-1)<=0||s.optLong("last_end",-1)<=0)throw new IllegalStateException("dry-run 后 eMMC/GPT 状态无效");
        JSONObject region=s.optJSONObject("region");
        if(region==null||!region.has("off")||!region.has("size")||!region.has("existing")||!region.has("dirty")
                ||!region.has("part")||!region.has("part_fs")||!region.has("part_count")||!region.has("foreign_image"))
            throw new IllegalStateException("helper region 状态字段缺失；拒绝安装");
        String existing=region.optString("existing","");
        if(!"yes".equals(existing)&&!"no".equals(existing))throw new IllegalStateException("helper region existing 状态未知；拒绝安装");
        int dirty=region.optInt("dirty",-2),part=region.optInt("part",-1),count=region.optInt("part_count",-1),foreign=region.optInt("foreign_image",-1);
        if(dirty < -1 || dirty > 16 || (part!=0&&part!=1) || count<0 || (foreign!=0&&foreign!=1))
            throw new IllegalStateException("helper region dirty/partition/image 状态字段无效；拒绝安装");
        if(region.optLong("off",-1)<0||region.optLong("size",-1)<0)throw new IllegalStateException("helper region 几何为负值");
        JSONArray parts=s.optJSONArray("parts");JSONObject sd=s.optJSONObject("sd");
        if(parts==null||sd==null||!sd.has("dev")||!sd.has("bytes")||!sd.has("small")||!sd.has("existing"))
            throw new IllegalStateException("helper 分区或 SD 状态缺失；拒绝安装");
        String sdExisting=sd.optString("existing","");
        if(!"yes".equals(sdExisting)&&!"no".equals(sdExisting))throw new IllegalStateException("SD 卡 existing 状态未知；拒绝安装");
    }

    private static void requireSnapshotSame(JSONObject before,JSONObject now) {
        if(before==null)throw new IllegalStateException("dry-run 缺少设备状态快照；请重新预览");
        for(String key:new String[]{"model","product","device","android","build","boot_completed","android_slot","linux_slot","slots_ok",
                "root_kind","magisk","magiskboot","busybox","linux_boot","disk","last_end","module"})sameJson(before,now,key,"设备状态变化：");
        sameNested(before,now,"misc",new String[]{"valid","bc"});
        sameNested(before,now,"region",new String[]{"off","size","existing","dirty","part","part_fs","part_count","foreign_image"});
        sameNested(before,now,"sd",new String[]{"dev","bytes","small","existing"});
        sameJson(before,now,"parts","设备 GPT 分区列表变化：");
        if(before.has("linux")!=now.has("linux"))throw new IllegalStateException("Linux 文件系统状态变化；请重新预览");
        if(before.has("linux"))sameJson(before,now,"linux","Linux 文件系统信息变化：");
    }

    private static void sameNested(JSONObject before,JSONObject now,String object,String[] keys) {
        JSONObject a=before.optJSONObject(object),b=now.optJSONObject(object);
        if(a==null||b==null)throw new IllegalStateException("helper "+object+" 状态变化或缺失");
        for(String key:keys)sameJson(a,b,key,"helper "+object+" 状态变化：");
    }

    private static void sameJson(JSONObject a,JSONObject b,String key,String message) {
        if(!a.has(key)||!b.has(key))throw new IllegalStateException(message+key+" 缺失");
        Object av=a.opt(key),bv=b.opt(key);
        String x=av==JSONObject.NULL?"<null>":String.valueOf(av),y=bv==JSONObject.NULL?"<null>":String.valueOf(bv);
        if(!x.equals(y))throw new IllegalStateException(message+key);
    }

    private void resumeRemoteJob() {
        if(busy||adb==null||adb.isClosed()||!remoteJobPending){setStatus("没有可续接的 helper job。");return;}
        final AdbConnection conn=adb;final String kind=remoteJobKind,name=remoteJobName;
        setBusy(true,"正在连接并继续读取目标端同一个 helper 任务；不会重新启动 job……");
        worker.execute(()->{
            int rc=-1;String failure=null;try{ensureHelperStaged(conn);rc=new WebInstallerClient(this,conn).follow((line,event)->onJobLine(line,event,new HashMap<>()));}catch(Exception e){failure=safeMessage(e);}
            final int result=rc;final String error=failure;
            if(error==null&&"install".equals(kind)){
                if(result==0){installWasAttempted=false;remoteJobPending=false;prefs.edit().putString("install_state","success").putString("install_name",name).commit();
                    if(waitingResume!=null)try{new MakeRoomTransaction(this,conn).markInstalled(waitingResume);}catch(Exception e){appendLog("安装成功但事务状态未更新；保留备份："+safeMessage(e));}}
                else{installWasAttempted=true;remoteJobPending=false;prefs.edit().putString("install_state","unknown").commit();}
            }else if(error==null){remoteJobPending=false;planSession=null;}
            else if("install".equals(kind)){installWasAttempted=true;remoteJobPending=true;}
            installInProgress=false;setBusy(false,error==null?("install".equals(kind)&&result==0?"续接的安装任务已成功结束（rc=0）；请按官方指南手动重启。":"目标 job 输出已续接完毕；安装前需重新运行问卷和 dry-run。"):("连接中断，保留原任务状态；重新连接后可再次 follow。"+error));
            if(alive)main.post(()->{updateHelperView();updateButtons();});
        });
    }

    private void ensureHelperStaged(AdbConnection conn) throws Exception {
        if (conn == null || conn.isClosed()) throw new IllegalStateException("目标连接已断开；未继续操作。");
        if (helperStageConnection != conn) {
            new WebInstallerClient(this, conn).stage();
            helperStageConnection = conn;
        }
    }

    private static String requireIdleHelperJob(JSONObject status) {
        JSONObject job = status == null ? null : status.optJSONObject("job");
        if (job == null) throw new IllegalStateException("目标 helper 未提供任务状态；为安全起见不扫描或清理。");
        String state = job.optString("state", "");
        if ("running".equals(state)) throw new IllegalStateException("目标端仍有 " + job.optString("kind", "未知") + " 任务运行；请先续接或等待任务结束，暂不清理。");
        if (!"none".equals(state) && !"done".equals(state) && !"interrupted".equals(state))
            throw new IllegalStateException("目标任务状态无法确认；保留现状，不清理。");
        return state;
    }

    private void detectTargetStageFiles() {
        if (busy) return;
        final AdbConnection conn = adb;
        if (conn == null || conn.isClosed() || target == null || !target.root) {
            if (targetCleanupView != null) targetCleanupView.setText("请先连接目标并确认已授予 root；本机无需 root。");
            updateButtons(); return;
        }
        targetStageInventory = null; targetStageInventoryConnection = null; updateButtons();
        setBusy(true, "正在检查目标 helper 任务和本应用暂存文件……");
        worker.execute(() -> {
            TargetStageCleanup.Inventory inventory = null; String failure = null;
            try {
                ensureHelperStaged(conn);
                WebInstallerClient client = new WebInstallerClient(this, conn);
                requireIdleHelperJob(client.status(false));
                List<String> lines = new ArrayList<>();
                int rc = conn.shell(TargetStageCleanup.inspectCommand(), lines::add, 30000);
                if (rc != 0) throw new IllegalStateException("目标暂存目录扫描失败，退出码 " + rc + "；未清理任何文件。");
                inventory = TargetStageCleanup.parse(lines);
            } catch (Exception e) { failure = safeMessage(e); }
            final TargetStageCleanup.Inventory found = inventory; final String error = failure;
            if (error == null) { targetStageInventory = found; targetStageInventoryConnection = conn; }
            String msg = error == null ? targetInventoryText(found) : "未完成扫描：" + error;
            setBusy(false, error == null ? "目标暂存文件扫描完成；未删除任何内容。" : msg);
            if (alive) main.post(() -> { if (targetCleanupView != null) targetCleanupView.setText(msg); updateButtons(); });
        });
    }

    private String targetInventoryText(TargetStageCleanup.Inventory inventory) {
        if (inventory == null) return "尚未检测。";
        if (!inventory.rootExists) return "没有发现本应用目标暂存目录。";
        StringBuilder s = new StringBuilder();
        if (inventory.files.isEmpty()) s.append("没有发现可清理的已知暂存文件。");
        else {
            s.append("检测到 ").append(inventory.files.size()).append(" 个文件，共 ").append(formatBytes(inventory.bytes())).append("：\n");
            for (TargetStageCleanup.RemoteFile f : inventory.files) s.append("• ").append(f).append('\n');
        }
        if (!inventory.unsafe.isEmpty()) s.append("发现符号链接或不安全项（不会跟随/删除）：").append(inventory.unsafe).append('\n');
        if (inventory.unknownEntries) s.append("目录里还有未知项目；为安全起见不会自动清理该目录。\n");
        s.append("仅扫描 /data/local/tmp/mu300-web；不处理 root 私有任务、备份、ZIP 或续装资料。");
        return s.toString();
    }

    private void confirmTargetStageCleanup() {
        final TargetStageCleanup.Inventory inventory = targetStageInventory;
        final AdbConnection conn = adb;
        if (busy || inventory == null || !inventory.canClean() || targetStageInventoryConnection != conn || conn == null || conn.isClosed()) {
            Toast.makeText(this, "请先在当前连接上完成一次安全扫描。", Toast.LENGTH_LONG).show(); return;
        }
        StringBuilder m = new StringBuilder("将从目标端本应用暂存目录删除以下文件：\n");
        for (TargetStageCleanup.RemoteFile f : inventory.files) m.append("• ").append(f).append('\n');
        m.append("\n总计：").append(formatBytes(inventory.bytes()));
        m.append("\n\n清理前会再次检查 helper 任务。若任务正在运行、文件有变化或发现目录异常，将拒绝清理。不会删除 /data/adb/mu300-web 中的活动任务、备份、ZIP 或待续装资料。是否继续？");
        new AlertDialog.Builder(this).setTitle("清理目标端暂存文件").setMessage(m.toString())
                .setNegativeButton("取消", null).setPositiveButton("清理", (d, w) -> cleanTargetStageFiles(conn, inventory)).show();
    }

    private void cleanTargetStageFiles(AdbConnection conn, TargetStageCleanup.Inventory inventory) {
        if (busy || conn != adb || conn.isClosed()) return;
        setBusy(true, "正在复核目标任务与文件大小，然后清理暂存文件……");
        worker.execute(() -> {
            String failure = null; int removed = inventory.files.size();
            try {
                ensureHelperStaged(conn);
                WebInstallerClient client = new WebInstallerClient(this, conn);
                requireIdleHelperJob(client.status(false));
                List<String> lines = new ArrayList<>();
                int rc = conn.shell(TargetStageCleanup.deleteCommand(inventory), lines::add, 30000);
                if (rc != 0 || !containsLine(lines, "MU300-OTG-CLEAN|DONE"))
                    throw new IllegalStateException("目标端拒绝清理或回执不完整（退出码 " + rc + "）；请重新扫描确认状态。");
                helperStageConnection = null;
                targetStageInventory = null; targetStageInventoryConnection = null;
            } catch (Exception e) { failure = safeMessage(e); }
            final String error = failure; final int count = removed;
            String msg = error == null ? "已清理目标暂存文件 " + count + " 个（约 " + formatBytes(inventory.bytes()) + "）。root 私有任务与续装资料未触碰；后续操作会按需重新推送 helper。" : "清理未完成：" + error;
            setBusy(false, msg);
            if (alive) main.post(() -> { if (targetCleanupView != null) targetCleanupView.setText(msg); updateButtons(); });
        });
    }

    private void updateHelperView() {
        if(helperView==null)return;
        if(remoteJobPending)helperView.setText("目标端任务待续接："+remoteJobKind+" / "+remoteJobName+"。点击计划按钮会 follow 同一个任务，不会重启。");
        else if(waitingResume!=null)helperView.setText((resumeValidated?"恢复事务与完整备份校验通过。":"待恢复事务尚未通过校验："+resumeValidationError)+"\n原始 ZIP："+waitingResume.zipName()+"；本地备份保存在 APK 私有目录。不要清除应用数据或卸载 APK。"+("prepared-request".equals(waitingResume.state())?"\n上次准备没有安全完成；禁止自动续接。":""));
        else if(webStatus!=null){JSONObject r=webStatus.optJSONObject("region");helperView.setText("官方 helper：v1；设备="+webStatus.optString("device","unknown")+"；内部 region="+(r==null?"unknown":formatBytes(r.optLong("size",0)))+"；空闲区采样="+(r==null?"unknown":Integer.toString(r.optInt("dirty",-1))));}
        else helperView.setText("尚未读取官方 helper 状态。");
    }

    private void beginMakeRoom() {
        if(busy||adb==null||adb.isClosed()||target==null||!target.ready()){setStatus("请先连接并授权目标设备。");return;}
        if(remoteJobPending){setStatus("目标端已有任务；请先 follow 同一 helper job。");return;}
        if(waitingResume!=null){
            if("prepared-request".equals(waitingResume.state())){setStatus("本机记录显示缩容准备未完整结束；为防误清数据，禁止自动续接或重新准备。保留设备与 APK 数据并人工检查。");return;}
            if(resumeValidated){setStatus("恢复事务已校验通过；请用本地保存的原始 ZIP 推送、运行 dry-run 并继续安装，不需要再次缩容。");if(stagedConnection!=adb)pushToTarget();return;}
            confirmPendingRecoveryIfPresent();return;
        }
        if(installWasAttempted){setStatus("已有未确认的安装任务；不能开始缩容。");return;}
        if(verifiedZip==null||!verifiedZip.exists()||!verifiedName.equals(selectedAssetName())){setStatus("先下载并校验官方 Magisk ZIP；事务将把此 ZIP 及其身份绑定到恢复记录。");return;}
        final AdbConnection conn=adb;final File zip=verifiedZip;final String name=verifiedName,hash=verifiedSha,system=systemSlug();
        setBusy(true,"执行只读 make-room 预检：设备身份、加密、双份 GPT、外来分区/image 与空闲区采样；不会写设备。");
        worker.execute(()->{
            MakeRoomPreflight.Result pre=null;JSONObject status=null;String error=null;
            try{ensureHelperStaged(conn);WebInstallerClient client=new WebInstallerClient(this,conn);status=client.status(true);pre=MakeRoomPreflight.inspect(conn,status);
                if(!MakeRoomTransaction.shaFile(zip).equals(hash))throw new IllegalStateException("本机 ZIP SHA-256 已变化；拒绝建立事务。");
            }catch(Exception e){error=safeMessage(e);}
            final MakeRoomPreflight.Result checked=pre;final JSONObject checkedStatus=status;final String failure=error;
            setBusy(false,failure==null?"只读 make-room 预检通过；尚未修改 GPT、misc 或 userdata。":"make-room 预检拒绝继续："+failure);
            if(failure==null&&alive)main.post(()->showMakeRoomSetup(checked,checkedStatus,zip,name,hash,system));
        });
    }

    private void showMakeRoomSetup(MakeRoomPreflight.Result p,JSONObject status,File zip,String name,String hash,String system) {
        if(p==null||!alive)return;
        LinearLayout box=column();box.setPadding(dp(18),dp(4),dp(18),0);
        addText(box,"只读预检已通过："+p.identitySummary()+"\nuserdata："+formatBytes(p.userdataSize*512L)+"；当前 Linux 内部空闲区："+formatBytes(status.optJSONObject("region").optLong("size",0))+"\n空闲区 dirty 16 点："+status.optJSONObject("region").optInt("dirty",-1)+"；未发现现有 Linux、mu300root 分区或外来 image。",13,INK,false,0,dp(8));
        EditText gib=new EditText(this);gib.setSingleLine(true);gib.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);gib.setText("4");gib.setHint("Linux region GiB");box.addView(gib,topParams(dp(4)));
        addText(box,"最多可申请约 "+formatBytes(p.maxLinuxBytes())+"，但必须为 Android 保留至少 4 GiB；Ubuntu 最小需求 1.6 GiB，OpenWrt 最小需求 800 MiB。上游按 2 MiB 对齐和 GPT 边界计算，下一步会显示实际 region；若低于系统最低需求将拒绝。",12,MUTED,false,dp(8),0);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("实验性 userdata 缩容空间").setView(box).setNegativeButton("取消",(d,w)->{}).setPositiveButton("计算并进入 ERASE 确认",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            MakeRoomProtocol.Split split;try{split=MakeRoomProtocol.split(gib.getText().toString(),p.diskSectors,p.userdataStart,p.gpt.usableEnd(),system);}
            catch(Exception e){gib.setError(safeMessage(e));return;}
            dialog.dismiss();showMakeRoomEraseConfirmation(p,zip,name,hash,system,gib.getText().toString().trim(),split);
        }));dialog.show();
    }

    private void showMakeRoomEraseConfirmation(MakeRoomPreflight.Result p,File zip,String name,String hash,String system,String gib,MakeRoomProtocol.Split split) {
        String msg="不可逆操作：会把 userdata 分区尾部让给 Linux，并在 misc 设置 Android recovery wipe。下次重启时 recovery 会清空目标 Android 的应用数据、照片、下载、帐户和设置；不能只清除某个应用。\n\n"
                +"设备："+p.identitySummary()+"\nAndroid userdata 缩容后约："+formatBytes(split.userdataBytes)+"（保留至少 4 GiB）\nLinux 申请："+gib+" GiB；对齐后实际 region："+formatBytes(split.regionBytes)+"\n绑定 ZIP："+name+"\n\n"
                +"缩容前必须通过目标 Shell 的 Magisk root 只读检查。请先用 Lino-UFI 备份 Magisk（面具）及要恢复的其他应用安装包；备份只含 APK，不含 App 数据。照片、下载、帐号和个人文件请另行完整备份。Lino-UFI 可用于投屏并给 Shell 授予 root。\n\n"
                +"控制手机会先保存官方 ZIP、作者恢复流程要求的 15 项分区/GPT 备份（不是完整 eMMC 备份）和 SHA256SUMS，再写 backup GPT → primary GPT → token marker → recovery BCB，并逐项读回校验。失败时不自动重试；除备份/事务记录外不自动恢复写入。不要清除 APK 数据或卸载。\n\n"
                +"这条 Android/OTG 缩容路径是实验性的，本次不连接真实设备。输入大写 ERASE 才会开始准备；准备完成后还需单独确认是否立即重启触发清除。";
        LinearLayout box=column();box.setPadding(dp(18),dp(4),dp(18),0);box.addView(text(msg,14,RED,true));
        CheckBox backupAck=new CheckBox(this);backupAck.setText("我已用 Lino-UFI 备份需保留的 APK，并另行备份个人文件");box.addView(backupAck,topParams(dp(8)));
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("输入 ERASE");box.addView(input,topParams(dp(10)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("最终确认：清空 Android userdata").setView(box).setNegativeButton("取消",(d,w)->{}).setPositiveButton("开始建立恢复事务",null).create();
        dialog.setOnShowListener(x->{Button ok=dialog.getButton(AlertDialog.BUTTON_POSITIVE);ok.setEnabled(false);input.addTextChangedListener(new android.text.TextWatcher(){
            private void refresh(){ok.setEnabled(backupAck.isChecked()&&"ERASE".contentEquals(input.getText()));}
            @Override public void beforeTextChanged(CharSequence s,int st,int count,int after){}@Override public void onTextChanged(CharSequence s,int st,int before,int count){refresh();}@Override public void afterTextChanged(android.text.Editable e){}
        });backupAck.setOnCheckedChangeListener((b,checked)->ok.setEnabled(checked&&"ERASE".contentEquals(input.getText())));ok.setOnClickListener(v->{if(backupAck.isChecked()&&"ERASE".contentEquals(input.getText())){dialog.dismiss();prepareMakeRoom(p,zip,name,hash,system,gib);}});});dialog.show();
    }

    private void prepareMakeRoom(MakeRoomPreflight.Result previous,File zip,String name,String hash,String system,String gib) {
        if(busy||adb==null||adb.isClosed())return;final AdbConnection conn=adb;
        setBusy(true,"已收到 ERASE 确认。重新跑只读预检、检查手机空间并生成完整备份；在所有备份校验通过前不写 eMMC。");
        worker.execute(()->{
            MakeRoomTransaction.Prepared prepared=null;String error=null;
            try{
                ensureHelperStaged(conn);
                WebInstallerClient client=new WebInstallerClient(this,conn);JSONObject status=client.status(true);MakeRoomPreflight.Result fresh=MakeRoomPreflight.inspect(conn,status);
                if(!previous.serial.equals(fresh.serial)||!previous.cid.equals(fresh.cid)||!previous.node.equals(fresh.node)||previous.diskSectors!=fresh.diskSectors||!previous.slot.equals(fresh.slot)||previous.userdataStart!=fresh.userdataStart||previous.userdataSize!=fresh.userdataSize)
                    throw new IllegalStateException("只读预检後目标设备或 userdata 几何发生变化；重新检查后再开始。");
                prepared=new MakeRoomTransaction(this,conn).prepare(fresh,zip,name,hash,system,gib);
            }catch(Exception e){error=safeMessage(e);}
            final MakeRoomTransaction.Prepared done=prepared;final String failure=error;
            if(failure==null){waitingResume=done.record;resumeValidated=false;resumeValidationError="必须先让 recovery 完成 Android userdata 清除，再重新连接校验";verifiedZip=done.record.zip;verifiedName=done.record.zipName();verifiedSha=done.record.zipSha();planSession=null;}
            setBusy(false,failure==null?"缩容事务已准备并逐项读回校验。尚未重启；Android 数据会在下次启动 recovery 时清除。":"缩容准备失败："+failure+"。保留本机事务和备份，勿关机、重试或清除 APK 数据。");
            if(failure==null&&alive)main.post(()->{updateHelperView();renderSelection();confirmPendingRecoveryIfPresent();});
        });
    }

    private void confirmPendingRecoveryIfPresent() {
        if(waitingResume==null||adb==null||adb.isClosed()||busy)return;
        final AdbConnection conn=adb;setBusy(true,"只读检查 misc recovery wipe 请求状态……");
        worker.execute(()->{
            boolean pending=false;String error=null;
            try{byte[] misc=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null'",2048);pending=MakeRoomProtocol.bcbPending(misc);}
            catch(Exception e){error=safeMessage(e);}
            final boolean wipe=pending;final String failure=error;setBusy(false,wipe?"misc 中仍有针对该事务的 recovery wipe 请求，目标尚未清除 userdata。":"misc 中未发现待处理 recovery wipe 请求；如恢复校验失败，不会触发重启。");
            if(wipe&&alive)main.post(()->showRecoveryRebootConfirmation(conn));
            else if(failure!=null)appendLog("读取 misc 只读状态失败："+failure);
        });
    }

    private void showRecoveryRebootConfirmation(AdbConnection conn) {
        if(conn!=adb||conn.isClosed()||waitingResume==null)return;
        new AlertDialog.Builder(this).setTitle("确认让 recovery 清空 Android userdata")
                .setMessage("本地已有与设备匹配的完整事务备份。misc 中的 boot-recovery wipe 请求仍待执行。现在重启将清空整块 Android userdata；不要选择此操作，除非重要数据已备份。若暂不重启，请注意下一次普通重启同样会触发该清除请求。")
                .setNegativeButton("暂不重启",(d,w)->{setStatus("缩容已准备；recovery wipe 请求仍写在 misc 中。下次重启会清除 Android userdata。不要清除 APK 数据或卸载。");})
                .setPositiveButton("重启并清除 Android 数据",(d,w)->rebootPreparedRecovery(conn)).show();
    }

    private void rebootPreparedRecovery(AdbConnection conn) {
        if(busy||conn!=adb||conn.isClosed())return;
        setBusy(true,"按已确认的 recovery BCB 重启目标；recovery 将清除 Android userdata……");
        worker.execute(()->{conn.reboot();conn.close();if(adb==conn)adb=null;target=null;stagedConnection=null;stagedZipPath="";stagedName="";stagedSha="";stagedManifest=null;
            setBusy(false,"目标已发出重启请求。等待 recovery 清除 Android userdata 并启动 Android；之后重新连接，应用会校验设备、GPT、token 与 15 项本地备份。");
            if(alive)main.post(()->{if(deviceView!=null)deviceView.setText("目标正在重启；保持供电，暂勿拔线。" );updateHelperView();updateButtons();});});
    }

    private void cleanupInbox(AdbConnection c, String inbox) {
        try { c.shell("rm -f " + shellQuote(inbox + "/" + verifiedName) + " 2>/dev/null; rmdir " + shellQuote(inbox) + " 2>/dev/null || true", null, 10000); }
        catch (Exception ignored) { }
    }

    private void cleanupRootStage(AdbConnection c, String path, String id) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{32}")) return;
        String dir = ROOT_STAGE + "/" + id;
        String script = "set -eu; [ ! -L " + shellQuote(ROOT_STAGE) + " ]; [ ! -L " + shellQuote(dir) + " ]; "
                + "rm -f " + shellQuote(path) + "; rmdir " + shellQuote(dir) + " 2>/dev/null || true";
        List<String> lines = new ArrayList<>();
        int rc = c.shell("su -c " + shellQuote(script), lines::add, 30000);
        if (rc != 0) throw new IllegalStateException("目标端暂存清理返回 " + rc);
        stagedConnection = null; stagedZipPath = ""; stagedName = ""; stagedSha = ""; stageId = "";
    }

    private static String parseShaLine(List<String> lines) {
        Pattern p = Pattern.compile("(?i)^\\s*([0-9a-f]{64})\\s+.*$");
        for (String line : lines) { Matcher m = p.matcher(line); if (m.matches()) return m.group(1).toLowerCase(Locale.ROOT); }
        return null;
    }
    private static boolean containsLine(List<String> lines, String needle) {
        for (String s : lines) if (s.contains(needle)) return true; return false;
    }

    private long remoteDataAvailableBytes(AdbConnection c) throws Exception {
        List<String> lines = new ArrayList<>();
        int rc = c.shell("su -c 'df -k /data' 2>&1", lines::add, 30000);
        if (rc != 0) throw new IllegalStateException("无法读取目标 /data 可用空间（退出码 " + rc + "）；为安全起见停止传输。");
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (!line.endsWith("/data")) continue;
            String[] columns = line.split("\\s+");
            if (columns.length < 6) continue;
            try { return Math.multiplyExact(Long.parseLong(columns[3]), 1024L); }
            catch (NumberFormatException | ArithmeticException ignored) { }
        }
        throw new IllegalStateException("无法解析目标 df 输出；不会继续向 /data 推送 ZIP。");
    }

    private interface ProgressSink { void update(long done, long total); }
    private long downloadToFile(String address, File destination, long expectedSize, ProgressSink sink) throws Exception {
        requireOfficialReleaseUrl(address);
        HttpURLConnection c = (HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(30000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MU300-Linux-OTG-Controller/" + BuildInfo.VERSION_NAME);
        int code = c.getResponseCode(); String host = c.getURL().getHost().toLowerCase(Locale.ROOT);
        if (!isOfficialAssetHost(host)) throw new IllegalStateException("GitHub 将下载重定向到了非官方文件主机");
        if (code < 200 || code >= 300) throw new IllegalStateException("下载 HTTP 状态 " + code);
        long declared = c.getContentLengthLong();
        if (declared > 0 && declared != expectedSize) throw new IllegalStateException("下载服务器报告大小与发布元数据不符");
        long done = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(destination)) {
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) != -1) {
                done += n; if (done > expectedSize || done > MAX_ZIP_BYTES) throw new IllegalStateException("下载超过官方大小限制");
                out.write(b, 0, n); sink.update(done, expectedSize);
            }
            out.flush();
        } finally { c.disconnect(); }
        return done;
    }

    private String readUrl(String address, int maxBytes) throws Exception {
        URL u = new URL(address); boolean api = "api.github.com".equalsIgnoreCase(u.getHost());
        if (api) {
            if (!"https".equalsIgnoreCase(u.getProtocol()) || !u.getPath().startsWith("/repos/dikeckaan/mu300-linux/"))
                throw new IllegalStateException("官方 API 地址异常");
        } else requireOfficialReleaseUrl(address);
        HttpURLConnection c = (HttpURLConnection)u.openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MU300-Linux-OTG-Controller/" + BuildInfo.VERSION_NAME);
        if (api) c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode(); String host = c.getURL().getHost().toLowerCase(Locale.ROOT);
        if (api ? !"api.github.com".equals(host) : !isOfficialAssetHost(host)) throw new IllegalStateException("官方内容重定向到非官方主机");
        if (code < 200 || code >= 300) throw new IllegalStateException("读取官方内容 HTTP 状态 " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > maxBytes) throw new IllegalStateException("官方响应超过允许大小"); out.write(b,0,n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private void requireOfficialReleaseUrl(String value) throws Exception {
        URL u = new URL(value);
        if (!"https".equalsIgnoreCase(u.getProtocol()) || !"github.com".equalsIgnoreCase(u.getHost())
                || !u.getPath().startsWith("/dikeckaan/mu300-linux/releases/download/"))
            throw new IllegalStateException("发布资产 URL 不符合官方 GitHub Releases 地址");
    }
    private boolean isOfficialAssetHost(String host) {
        return "github.com".equals(host) || "release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host);
    }
    private String checksumFor(String manifest, String filename) {
        for (String line : manifest.split("\\r?\\n")) {
            String t = line.trim(); if (t.isEmpty()) continue;
            String[] pieces = t.split("\\s+", 2);
            if (pieces.length != 2 || !pieces[0].matches("(?i)[0-9a-f]{64}")) continue;
            String name = pieces[1].trim(); while (name.startsWith("*")) name = name.substring(1);
            int slash = name.lastIndexOf('/'); if (slash >= 0) name = name.substring(slash + 1);
            if (filename.equals(name)) return pieces[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }
    private String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[64*1024]; int n; while ((n=in.read(b))!=-1) md.update(b,0,n); }
        StringBuilder s = new StringBuilder(64); for (byte x : md.digest()) s.append(String.format(Locale.ROOT,"%02x",x&255)); return s.toString();
    }

    private void setBusy(boolean on, String message) {
        busy = on;
        if (alive) main.post(() -> {
            setStatus(message);
            if (progress != null) progress.setIndeterminate(on && (message.contains("握手") || message.contains("安装器") || message.contains("安装。") || message.contains("dry-run") || message.contains("预检")));
            updateButtons();
        });
    }
    private void updateButtons() {
        boolean connected = adb != null && !adb.isClosed() && target != null && target.ready();
        boolean transportConnected = adb != null && !adb.isClosed() && target != null && target.root;
        if (scanButton != null) scanButton.setEnabled(!busy);
        if (connectButton != null) connectButton.setEnabled(!busy && !usbCandidates.isEmpty());
        if (releaseButton != null) releaseButton.setEnabled(!busy);
        boolean fixedResume=waitingResume!=null;
        if (downloadButton != null) downloadButton.setEnabled(!busy && !fixedResume && selectedAsset()!=null);
        if (pushButton != null) pushButton.setEnabled(!busy && transportConnected && verifiedZip!=null && verifiedZip.exists()
                && verifiedName.equals(installAssetName()) && !installWasAttempted && (!fixedResume||resumeValidated) && !remoteJobPending);
        if (planButton != null) planButton.setEnabled(!busy && connected && (remoteJobPending || (!installWasAttempted&&stagedConnection==adb&&!stagedZipPath.isEmpty()&&stagedName.equals(installAssetName())&&stagedSha.equals(verifiedSha))));
        if (installButton != null) installButton.setEnabled(!busy && connected && (remoteJobPending || (!installWasAttempted&&planSession!=null
                &&stagedConnection==adb&&stagedName.equals(installAssetName())&&stagedSha.equals(planSession.zipSha))));
        if (makeRoomButton != null) {
            makeRoomButton.setText(waitingResume != null ? "继续格式化后的安装" : "查看只读预检与 Linux 空间设置");
            makeRoomButton.setEnabled(!busy && connected && !remoteJobPending);
        }
        if (targetScanButton != null) targetScanButton.setEnabled(!busy && transportConnected);
        if (targetCleanButton != null) targetCleanButton.setEnabled(!busy && transportConnected && !remoteJobPending
                && targetStageInventory != null && targetStageInventory.canClean() && targetStageInventoryConnection == adb);
        if (osSpinner != null) osSpinner.setEnabled(!busy&&!fixedResume);
        if (kernelSpinner != null) kernelSpinner.setEnabled(!busy&&!fixedResume);
        updateStepChrome();
    }
    private void setStatus(String s) {
        if (alive) AppRuntimeLog.record("STATUS", s);
        if (!alive) return;
        main.post(() -> { if (statusView != null) statusView.setText(s); updateStepChrome(); });
    }
    private void appendLog(String raw) {
        String safe = redact(raw);
        AppRuntimeLog.record("APP", safe);
        if (!alive) return;
        main.post(() -> {
            if (logView == null) return;
            logs.append(safe).append('\n'); if (logs.length() > 50000) logs.delete(0, logs.length()-50000);
            logView.setText(logs.toString());
            if (currentStep == 5 && stepScroll != null) stepScroll.post(() -> stepScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    private String redact(String s) {
        if (s == null) return "未知错误";
        String x = s.replaceAll("(?i)\\b(IMEI|IMSI|ICCID|serial(?:\\s*number)?|电话号码|序列号)\\s*[:=：]?\\s*[A-Z0-9-]{6,24}", "$1=[已遮盖]");
        x = x.replaceAll("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b", "[MAC 已遮盖]");
        x = x.replaceAll("\\b[0-9]{14,22}\\b", "[数字已遮盖]");
        return x;
    }
    private String safeMessage(Exception e) {
        String m = e.getMessage(); return redact(m == null ? e.getClass().getSimpleName() : m);
    }
    private void copyLog() {
        ClipboardManager c = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (c != null) c.setPrimaryClip(ClipData.newPlainText("MU300 OTG log", logs.toString()));
        Toast.makeText(this, "已复制已遮盖的过程记录。", Toast.LENGTH_SHORT).show();
    }
    private void exportRuntimeLog() {
        AppRuntimeLog.record("EXPORT", "user requested runtime log export");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, AppRuntimeLog.exportFileName());
        try { startActivityForResult(intent, REQUEST_EXPORT_RUNTIME_LOG); }
        catch (Exception e) {
            AppRuntimeLog.recordThrowable("ERROR", "could not open system document picker", e);
            Toast.makeText(this, "无法打开系统文件选择器。", Toast.LENGTH_LONG).show();
        }
    }
    private void openGuide() {
        openUrl(GUIDE_URL);
    }
    private void openUrl(String address) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address))); }
        catch (Exception e) { Toast.makeText(this, "无法打开网页。", Toast.LENGTH_SHORT).show(); }
    }
    private String linoUfiButtonLabel() {
        return getPackageManager().getLaunchIntentForPackage(UFI_PACKAGE) == null ? "获取 Lino-UFI / UFI-TOOLS 使用说明" : "打开 Lino-UFI（备份、投屏与 UFI-TOOLS）";
    }
    private void openLinoUfi() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(UFI_PACKAGE);
            if (launch != null) startActivity(launch);
            else startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(UFI_GUIDE_URL)));
        } catch (Exception e) { Toast.makeText(this, "无法打开 Lino-UFI 或其使用说明。", Toast.LENGTH_SHORT).show(); }
    }
    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String safeKeyName(String s) { return (s == null ? "android" : s).replaceAll("[^A-Za-z0-9._:-]", "_"); }
    private static String formatBytes(long n) {
        if (n < 1024) return n + " B"; if (n < 1024*1024) return String.format(Locale.ROOT,"%.1f KiB",n/1024.0);
        if (n < 1024L*1024L*1024L) return String.format(Locale.ROOT,"%.1f MiB",n/(1024.0*1024.0));
        return String.format(Locale.ROOT,"%.2f GiB",n/(1024.0*1024.0*1024.0));
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card(String heading) {
        LinearLayout box = column(); box.setPadding(dp(14),dp(14),dp(14),dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1),Color.rgb(223,231,231)); box.setBackground(bg);
        LinearLayout.LayoutParams p = params(-1,-2); p.topMargin = dp(12); page.addView(box,p);
        TextView h = text(heading,16,INK,true); box.addView(h,params(-1,-2)); return box;
    }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(null,android.graphics.Typeface.BOLD); t.setLineSpacing(dp(3),1f); return t;
    }
    private void addText(LinearLayout parent,String s,int size,int color,boolean bold,int top,int bottom) {
        TextView t = text(s,size,color,bold); LinearLayout.LayoutParams p=params(-1,-2); p.topMargin=dp(top); p.bottomMargin=dp(bottom); parent.addView(t,p);
    }
    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(TEAL); return b;
    }
    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this); ArrayAdapter<String> a = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s;
    }
    private LinearLayout.LayoutParams params(int w,int h) { return new LinearLayout.LayoutParams(w,h); }
    private LinearLayout.LayoutParams topParams(int top) { LinearLayout.LayoutParams p=params(-1,-2); p.topMargin=top; return p; }
    private LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0,-2,w); }
    private int dp(float x) { return (int)(x*getResources().getDisplayMetrics().density+0.5f); }
}
