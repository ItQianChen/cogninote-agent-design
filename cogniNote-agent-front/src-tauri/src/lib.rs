use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    thread,
    time::{Duration, Instant},
};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

use tauri::{
    path::BaseDirectory,
    App, AppHandle, Manager, WebviewUrl, WebviewWindowBuilder,
};
use tauri_plugin_dialog::{DialogExt, MessageDialogKind};

const APP_NAME: &str = "CogniNote";
const BACKEND_RESOURCE_DIR: &str = "backend/CogniNoteBackend";
const BACKEND_EXE_NAME: &str = "CogniNoteBackend.exe";
const MIN_PORT: u16 = 18080;
const MAX_PORT: u16 = 18120;
const STARTUP_TIMEOUT: Duration = Duration::from_secs(45);
const HEALTH_CHECK_INTERVAL: Duration = Duration::from_millis(500);
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

struct BackendProcess {
    child: Mutex<Option<Child>>,
    port: u16,
    log_path: PathBuf,
}

impl Drop for BackendProcess {
    fn drop(&mut self) {
        if let Ok(mut child_guard) = self.child.lock() {
            if let Some(mut child) = child_guard.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(setup_desktop)
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::CloseRequested { .. }) {
                shutdown_backend(&window.app_handle());
            }
        })
        .run(tauri::generate_context!())
        .expect("failed to run CogniNote desktop shell");
}

fn setup_desktop(app: &mut App) -> Result<(), Box<dyn std::error::Error>> {
    match start_backend_and_open_window(app) {
        Ok(()) => Ok(()),
        Err(error) => {
            show_startup_error(app.handle(), &error);
            Err(std::io::Error::new(std::io::ErrorKind::Other, error).into())
        }
    }
}

fn start_backend_and_open_window(app: &mut App) -> Result<(), String> {
    let port = select_available_port()?;
    let backend_exe = resolve_backend_exe(app)?;
    let log_path = prepare_backend_log_path()?;
    let child = spawn_backend(&backend_exe, port, &log_path)?;

    if let Err(error) = wait_until_backend_ready(port) {
        let mut child = child;
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }

    // Tauri 配置里不预创建窗口，避免后端未就绪时显示一个不可用的空白页面。
    // 健康检查通过后再把 WebView 指到 Spring Boot 同源页面，前端现有 /api 相对路径即可继续工作。
    if let Err(error) = open_main_window(app.handle(), port) {
        let mut child = child;
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }
    app.manage(BackendProcess {
        child: Mutex::new(Some(child)),
        port,
        log_path,
    });
    Ok(())
}

fn select_available_port() -> Result<u16, String> {
    for port in MIN_PORT..=MAX_PORT {
        if TcpListener::bind(("127.0.0.1", port)).is_ok() {
            return Ok(port);
        }
    }
    Err(format!(
        "没有找到可用端口，请释放 {}-{} 之间的本地端口后重试。",
        MIN_PORT, MAX_PORT
    ))
}

fn resolve_backend_exe(app: &App) -> Result<PathBuf, String> {
    // jpackage app-image 不是单文件程序，exe 旁边的 app/ 与 runtime/ 目录缺一不可。
    // 因此 Tauri 打包时必须携带整个 backend/CogniNoteBackend 目录，再从资源目录中定位 exe。
    let resource_dir = app
        .path()
        .resolve(BACKEND_RESOURCE_DIR, BaseDirectory::Resource)
        .map_err(|error| format!("无法定位后端资源目录：{error}"))?;
    let exe = resource_dir.join(BACKEND_EXE_NAME);
    if exe.exists() {
        Ok(exe)
    } else {
        Err(format!(
            "未找到后端启动器：{}\n请先运行 scripts/build-desktop-backend.ps1 生成 jpackage app-image。",
            exe.display()
        ))
    }
}

fn prepare_backend_log_path() -> Result<PathBuf, String> {
    let app_data = std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .ok_or_else(|| "无法读取 APPDATA 环境变量，不能创建桌面启动日志。".to_string())?;
    let log_dir = app_data.join(APP_NAME).join("logs");
    fs::create_dir_all(&log_dir)
        .map_err(|error| format!("无法创建日志目录 {}：{error}", log_dir.display()))?;
    Ok(log_dir.join("desktop-backend.log"))
}

fn spawn_backend(backend_exe: &Path, port: u16, log_path: &Path) -> Result<Child, String> {
    let stdout = OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .map_err(|error| format!("无法打开后端日志 {}：{error}", log_path.display()))?;
    let stderr = stdout
        .try_clone()
        .map_err(|error| format!("无法复用后端日志句柄：{error}"))?;

    append_log_line(
        log_path,
        &format!(
            "\n==== Starting backend: {} on port {} ====",
            backend_exe.display(),
            port
        ),
    );

    let mut command = Command::new(backend_exe);
    command
        .env("COGNINOTE_PORT", port.to_string())
        .env("COGNINOTE_DESKTOP", "true")
        .current_dir(backend_exe.parent().unwrap_or_else(|| Path::new(".")))
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));
    configure_backend_process_window(&mut command);

    command
        .spawn()
        .map_err(|error| format!("无法启动后端进程：{error}"))
}

fn configure_backend_process_window(command: &mut Command) {
    // jpackage 生成的后端启动器在 Windows 下可能按控制台程序启动。
    // 桌面壳已经把输出重定向到日志文件，这里显式禁止子进程创建 cmd 窗口，避免用户双击主程序后看到后台服务窗口常驻。
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);
}

fn wait_until_backend_ready(port: u16) -> Result<(), String> {
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    while Instant::now() < deadline {
        if is_system_status_ready(port) {
            return Ok(());
        }
        thread::sleep(HEALTH_CHECK_INTERVAL);
    }
    Err(format!(
        "后端启动超时。请查看日志：%APPDATA%\\{}\\logs\\desktop-backend.log",
        APP_NAME
    ))
}

fn is_system_status_ready(port: u16) -> bool {
    let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(800)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(800)));

    let request = b"GET /api/system/status HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    if stream.write_all(request).is_err() {
        return false;
    }

    let mut response = String::new();
    stream.read_to_string(&mut response).is_ok() && response.starts_with("HTTP/1.1 200")
}

fn open_main_window(app: &AppHandle, port: u16) -> Result<(), String> {
    let url = format!("http://127.0.0.1:{port}/");
    WebviewWindowBuilder::new(
        app,
        "main",
        WebviewUrl::External(url.parse().map_err(|error| {
            format!("无法生成桌面窗口 URL：{error}")
        })?),
    )
    .title(APP_NAME)
    .inner_size(1280.0, 820.0)
    .min_inner_size(960.0, 640.0)
    .center()
    .build()
    .map_err(|error| format!("无法创建桌面窗口：{error}"))?;
    Ok(())
}

fn shutdown_backend(app: &AppHandle) {
    if let Some(state) = app.try_state::<BackendProcess>() {
        if let Ok(mut child_guard) = state.child.lock() {
            if let Some(mut child) = child_guard.take() {
                append_log_line(
                    &state.log_path,
                    &format!("==== Stopping backend on port {} ====", state.port),
                );
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

fn show_startup_error(app: &AppHandle, message: &str) {
    app.dialog()
        .message(message)
        .kind(MessageDialogKind::Error)
        .title("CogniNote 启动失败")
        .blocking_show();
}

fn append_log_line(log_path: &Path, line: &str) {
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(log_path) {
        let _ = writeln!(file, "{line}");
    }
}
