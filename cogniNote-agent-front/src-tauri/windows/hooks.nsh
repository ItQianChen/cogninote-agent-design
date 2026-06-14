!macro COGNINOTE_KILL_PROCESS PROCESS_NAME
  DetailPrint "Stopping ${PROCESS_NAME} if it is still running..."
  nsExec::ExecToLog 'taskkill /IM "${PROCESS_NAME}" /T /F'
  Pop $0
!macroend

!macro COGNINOTE_ABORT_IF_EXISTS PATH MESSAGE
  IfFileExists "${PATH}" 0 +2
    Abort "${MESSAGE}"
!macroend

!macro COGNINOTE_CLEAN_INSTALL_DIR
  DetailPrint "Cleaning previous CogniNote installation resources..."
  /*
   * Treat the install directory as one immutable version snapshot. NSIS can continue after a
   * partially locked cleanup, but that leaves old frontend/backend bits mixed with the new build.
   */
  SetOverwrite on
  Sleep 1500
  RMDir /r "$INSTDIR\backend"
  Delete "$INSTDIR\${MAINBINARYNAME}.exe"
  Delete "$INSTDIR\CogniNote.exe"
  Delete "$INSTDIR\cogninote-agent.exe"
  !insertmacro COGNINOTE_ABORT_IF_EXISTS "$INSTDIR\backend\CogniNoteBackend\CogniNoteBackend.exe" "旧版 CogniNote 后端仍被占用，安装已中止。请关闭 CogniNote 后重新运行安装器。"
  !insertmacro COGNINOTE_ABORT_IF_EXISTS "$INSTDIR\backend\CogniNoteBackend\app\cogninote-agent-design.jar" "旧版 CogniNote 后端资源未能清理，安装已中止。请关闭 CogniNote 后重新运行安装器。"
  !insertmacro COGNINOTE_ABORT_IF_EXISTS "$INSTDIR\${MAINBINARYNAME}.exe" "旧版 CogniNote 主程序仍被占用，安装已中止。请关闭 CogniNote 后重新运行安装器。"
  !insertmacro COGNINOTE_ABORT_IF_EXISTS "$INSTDIR\CogniNote.exe" "旧版 CogniNote 主程序仍被占用，安装已中止。请关闭 CogniNote 后重新运行安装器。"
!macroend

!macro COGNINOTE_CLEAN_WEBVIEW_CACHE
  DetailPrint "Cleaning CogniNote WebView2 cache while preserving user data..."
  /*
   * WebView2 keeps HTTP and bytecode caches outside $INSTDIR. If an old cached
   * index.html survives an upgrade, it can keep loading old Vite asset hashes
   * even though the installer has copied the new desktop binaries.
   */
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\Cache"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\Code Cache"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\GPUCache"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\DawnGraphiteCache"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\DawnWebGPUCache"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\Service Worker"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\Default\CacheStorage"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\component_crx_cache"
  RMDir /r "$LOCALAPPDATA\com.itqianchen.cogninote\EBWebView\extensions_crx_cache"
!macroend

!macro COGNINOTE_DELETE_SHORTCUTS
  DetailPrint "Removing stale CogniNote shortcuts..."
  Delete "$DESKTOP\CogniNote.lnk"
  Delete "$DESKTOP\CogniNote Agent.lnk"
  Delete "$COMMONDESKTOP\CogniNote.lnk"
  Delete "$COMMONDESKTOP\CogniNote Agent.lnk"
  Delete "$SMPROGRAMS\CogniNote.lnk"
  Delete "$SMPROGRAMS\CogniNote Agent.lnk"
  Delete "$SMPROGRAMS\CogniNote\CogniNote.lnk"
  Delete "$SMPROGRAMS\CogniNote\CogniNote Agent.lnk"
  RMDir "$SMPROGRAMS\CogniNote"
!macroend

!macro COGNINOTE_REFRESH_SHORTCUT_ICON SHORTCUT_PATH
  ${If} ${FileExists} "${SHORTCUT_PATH}"
    Delete "${SHORTCUT_PATH}"
    CreateShortcut "${SHORTCUT_PATH}" "$INSTDIR\${MAINBINARYNAME}.exe" "" "$INSTDIR\${MAINBINARYNAME}.exe" 0
    !insertmacro SetLnkAppUserModelId "${SHORTCUT_PATH}"
  ${EndIf}
!macroend

!macro COGNINOTE_REFRESH_INSTALLED_ICONS
  DetailPrint "Refreshing CogniNote shortcut icons..."
  /*
   * Tauri's generated shortcuts can leave IconLocation empty. After an app icon change,
   * Explorer may keep showing the previous cached icon unless the shortcut points at the
   * installed executable explicitly and the shell icon cache is notified.
   */
  !if "${STARTMENUFOLDER}" != ""
    !insertmacro COGNINOTE_REFRESH_SHORTCUT_ICON "$SMPROGRAMS\$AppStartMenuFolder\${PRODUCTNAME}.lnk"
  !else
    !insertmacro COGNINOTE_REFRESH_SHORTCUT_ICON "$SMPROGRAMS\${PRODUCTNAME}.lnk"
  !endif
  !insertmacro COGNINOTE_REFRESH_SHORTCUT_ICON "$DESKTOP\${PRODUCTNAME}.lnk"
  !insertmacro COGNINOTE_REFRESH_SHORTCUT_ICON "$COMMONDESKTOP\${PRODUCTNAME}.lnk"
  System::Call "shell32::SHChangeNotify(i,i,i,i) (0x08000000, 0x1000, 0, 0)"
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro COGNINOTE_KILL_PROCESS "CogniNote.exe"
  !insertmacro COGNINOTE_KILL_PROCESS "cogninote-agent.exe"
  !insertmacro COGNINOTE_KILL_PROCESS "CogniNoteBackend.exe"
  !insertmacro COGNINOTE_CLEAN_INSTALL_DIR
  !insertmacro COGNINOTE_CLEAN_WEBVIEW_CACHE
  !insertmacro COGNINOTE_DELETE_SHORTCUTS
!macroend

!macro NSIS_HOOK_POSTINSTALL
  !insertmacro COGNINOTE_REFRESH_INSTALLED_ICONS
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro COGNINOTE_KILL_PROCESS "CogniNote.exe"
  !insertmacro COGNINOTE_KILL_PROCESS "cogninote-agent.exe"
  !insertmacro COGNINOTE_KILL_PROCESS "CogniNoteBackend.exe"
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  DetailPrint "Removing CogniNote installation leftovers while preserving user data..."
  RMDir /r "$INSTDIR\backend"
  Delete "$INSTDIR\CogniNote.exe"
  Delete "$INSTDIR\cogninote-agent.exe"
  !insertmacro COGNINOTE_CLEAN_WEBVIEW_CACHE
  !insertmacro COGNINOTE_DELETE_SHORTCUTS
  RMDir "$INSTDIR"
!macroend
