import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

const THEME_STORAGE_KEY = 'cogninote-theme'
const DEFAULT_THEME = 'system'

export const THEME_OPTIONS = [
  { label: '跟随系统', value: 'system' },
  { label: '日间', value: 'light' },
  { label: '夜间', value: 'dark' }
]

export const useThemeStore = defineStore('theme', () => {
  const theme = ref(readInitialTheme())
  const systemPrefersDark = ref(readSystemPreference())
  const effectiveTheme = computed(() => theme.value === 'system'
    ? (systemPrefersDark.value ? 'dark' : 'light')
    : theme.value
  )
  const isDark = computed(() => effectiveTheme.value === 'dark')

  function setTheme(nextTheme) {
    theme.value = normalizeTheme(nextTheme)
    window.localStorage.setItem(THEME_STORAGE_KEY, theme.value)
    applyTheme()
  }

  function applyTheme() {
    if (typeof document === 'undefined') {
      return
    }
    // theme 是用户选择，resolvedTheme 是 CSS 真正生效的浅/深色；system 模式必须分开记录。
    const resolvedTheme = effectiveTheme.value
    const root = document.documentElement
    root.classList.toggle('theme-light', resolvedTheme === 'light')
    root.classList.toggle('theme-dark', resolvedTheme === 'dark')
    root.classList.toggle('dark', resolvedTheme === 'dark')
    root.dataset.theme = theme.value
    root.dataset.resolvedTheme = resolvedTheme
  }

  function initializeSystemThemeListener() {
    if (typeof window === 'undefined' || !window.matchMedia) {
      return
    }
    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    const handleChange = (event) => {
      systemPrefersDark.value = event.matches
    }
    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener('change', handleChange)
    } else if (mediaQuery.addListener) {
      mediaQuery.addListener(handleChange)
    }
  }

  watch(effectiveTheme, () => applyTheme())
  initializeSystemThemeListener()
  applyTheme()

  return {
    theme,
    effectiveTheme,
    isDark,
    setTheme,
    applyTheme
  }
})

function normalizeTheme(value) {
  return THEME_OPTIONS.some((option) => option.value === value) ? value : DEFAULT_THEME
}

function readInitialTheme() {
  if (typeof window === 'undefined') {
    return DEFAULT_THEME
  }
  // 旧版本只保存 dark/light；这两个值仍然有效，缺省或异常值迁移为跟随系统。
  return normalizeTheme(window.localStorage.getItem(THEME_STORAGE_KEY))
}

function readSystemPreference() {
  return typeof window !== 'undefined'
    && window.matchMedia
    && window.matchMedia('(prefers-color-scheme: dark)').matches
}
