!macro COGNINOTE_KILL_PROCESS PROCESS_NAME
  DetailPrint "Stopping ${PROCESS_NAME} if it is still running..."
  nsExec::ExecToLog 'taskkill /IM "${PROCESS_NAME}" /T /F'
  Pop $0
!macroend

!macro COGNINOTE_CLEAN_INSTALL_DIR
  DetailPrint "Cleaning previous CogniNote installation resources..."
  RMDir /r "$INSTDIR\backend"
  Delete "$INSTDIR\CogniNote.exe"
  Delete "$INSTDIR\cogninote-agent.exe"
!macroend

!macro COGNINOTE_DELETE_SHORTCUTS
  DetailPrint "Removing stale CogniNote shortcuts..."
  Delete "$DESKTOP\CogniNote.lnk"
  Delete "$DESKTOP\CogniNote Agent.lnk"
  Delete "$SMPROGRAMS\CogniNote.lnk"
  Delete "$SMPROGRAMS\CogniNote Agent.lnk"
  Delete "$SMPROGRAMS\CogniNote\CogniNote.lnk"
  Delete "$SMPROGRAMS\CogniNote\CogniNote Agent.lnk"
  RMDir "$SMPROGRAMS\CogniNote"
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro COGNINOTE_KILL_PROCESS "CogniNote.exe"
  !insertmacro COGNINOTE_KILL_PROCESS "cogninote-agent.exe"
  !insertmacro COGNINOTE_KILL_PROCESS "CogniNoteBackend.exe"
  !insertmacro COGNINOTE_CLEAN_INSTALL_DIR
  !insertmacro COGNINOTE_DELETE_SHORTCUTS
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
  !insertmacro COGNINOTE_DELETE_SHORTCUTS
  RMDir "$INSTDIR"
!macroend
