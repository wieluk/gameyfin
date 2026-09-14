export function downloadSave(saveId: number) {
    window.open(`/saves/${saveId}`, '_top');
}
