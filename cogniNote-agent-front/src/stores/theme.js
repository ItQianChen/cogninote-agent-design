import {computed, ref} from 'vue'
import {defineStore} from 'pinia'

const THEME_STORAGE_KEY = 'cogninote-theme'
const DEFAULT_THEME = 'dark'

export const THEME_OPTIONS = [
    {label: '夜间', value: 'dark'},
    {label: '日间', value: 'light'}
]

export const useThemeStore = defineStore('theme', () => {
    const theme = ref(readInitialTheme())
    const isDark = computed(() => theme.value === 'dark')

    function setTheme(nextTheme) {
        theme.value = THEME_OPTIONS.some((option) => option.value === nextTheme) ? nextTheme : DEFAULT_THEME
        applyTheme()
        window.localStorage.setItem(THEME_STORAGE_KEY, theme.value)
    }

    function applyTheme() {
        // 主题类挂在 html 上，避免页面切换时丢失；CSS 模块只需要按 theme-light 做覆盖。
        document.documentElement.classList.toggle('theme-light', theme.value === 'light')
        document.documentElement.classList.toggle('theme-dark', theme.value === 'dark')
        document.documentElement.classList.toggle('dark', theme.value === 'dark')
    }

    applyTheme()

    return {
        theme,
        isDark,
        setTheme,
        applyTheme
    }
})

function readInitialTheme() {
    if (typeof window === 'undefined') {
        return DEFAULT_THEME
    }
    const storedTheme = window.localStorage.getItem(THEME_STORAGE_KEY)
    return THEME_OPTIONS.some((option) => option.value === storedTheme) ? storedTheme : DEFAULT_THEME
}
