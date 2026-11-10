/**
 * 화면 테마 (라이트 / 다크) : OS(브라우저) 설정(prefers-color-scheme)을 따른다.
 * <p>
 * 처음 값은 index.html 의 인라인 스크립트가 렌더링 전에 <html data-theme / data-bs-theme> 에 넣고 (깜빡임 방지),
 * 여기서는 사용 중에 OS 테마가 바뀌면 다시 반영한다.
 */
export function followSystemTheme() {
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const apply = () => {
    const theme = media.matches ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.setAttribute('data-bs-theme', theme); // DataTables CSS 다크 스타일
  };
  apply();
  media.addEventListener('change', apply);
}
