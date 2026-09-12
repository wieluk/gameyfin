import {useEffect, useState} from "react";
import {SaveSyncEndpoint} from "Frontend/generated/endpoints";
import GameSaveDto from "Frontend/generated/org/gameyfin/app/saves/dto/GameSaveDto";
import SaveVersionsTable from "Frontend/components/general/SaveVersionsTable";
import {useSaveSyncEnabled} from "Frontend/util/saveSync";

export default function GameSaves({gameId}: { gameId: number }) {
    const enabled = useSaveSyncEnabled();
    const [saves, setSaves] = useState<GameSaveDto[]>([]);

    async function reload() {
        // Few enough saves per user that a per-game round trip would buy nothing
        const mine = await SaveSyncEndpoint.getMySaves();
        setSaves(mine.filter(save => save.gameId === gameId));
    }

    useEffect(() => {
        if (enabled) void reload();
    }, [enabled, gameId]);

    if (!enabled || saves.length === 0) return null;

    return (
        <div className="flex flex-col gap-2">
            <p className="text-default-500">Cloud saves</p>
            <SaveVersionsTable saves={saves} label="Cloud saves for this game" onChange={reload}/>
        </div>
    );
}
