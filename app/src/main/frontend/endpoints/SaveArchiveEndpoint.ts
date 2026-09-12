export function downloadSave(gameId: number, saveId: number) {
    window.open(`/saves/game/${gameId}/${saveId}`, '_top');
}
