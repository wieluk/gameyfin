import {useEffect, useMemo, useState} from "react";
import {Accordion, Button, Label, ListBox, ProgressBar, SearchField, Select, Spinner} from "@heroui/react";
import {CloudSlashIcon, TrashIcon} from "@phosphor-icons/react";
import {SaveSyncEndpoint, UserEndpoint} from "Frontend/generated/endpoints";
import GameSaveDto from "Frontend/generated/org/gameyfin/app/saves/dto/GameSaveDto";
import ExtendedUserInfoDto from "Frontend/generated/org/gameyfin/app/users/dto/ExtendedUserInfoDto";
import SaveVersionsTable from "Frontend/components/general/SaveVersionsTable";
import ConfirmModal from "Frontend/components/general/modals/ConfirmModal";
import {humanFileSize, isAdmin, timeUntil} from "Frontend/util/utils";
import {useAuth} from "Frontend/util/auth";
import {useSaveSyncEnabled} from "Frontend/util/saveSync";

/** Select value for the signed-in user's own saves. */
const MY_SAVES = "me";

interface GameGroup {
    key: string;
    gameId?: number;
    gameTitle: string;
    versions: GameSaveDto[];
    totalBytes: number;
    lastSyncedAt?: string;
}

function groupKey(save: GameSaveDto): string {
    return save.gameId !== undefined ? `game-${save.gameId}` : `removed-${save.gameTitle}`;
}

function groupByGame(saves: GameSaveDto[]): GameGroup[] {
    const groups = new Map<string, GameGroup>();

    for (const save of saves) {
        const key = groupKey(save);
        const group = groups.get(key) ?? {
            key,
            gameId: save.gameId,
            gameTitle: save.gameTitle ?? "Unknown game",
            versions: [],
            totalBytes: 0,
            lastSyncedAt: save.createdAt
        };
        group.versions.push(save);
        group.totalBytes += save.sizeBytes;
        groups.set(key, group);
    }

    return Array.from(groups.values())
        .sort((a, b) => (b.lastSyncedAt ?? "").localeCompare(a.lastSyncedAt ?? ""));
}

export default function CloudSavesView() {
    const auth = useAuth();
    const admin = isAdmin(auth);
    const enabled = useSaveSyncEnabled();

    const [saves, setSaves] = useState<GameSaveDto[]>([]);
    const [quotaBytes, setQuotaBytes] = useState(0);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");
    const [pendingDeletion, setPendingDeletion] = useState<{ label: string, ids: number[] } | undefined>();

    const [users, setUsers] = useState<ExtendedUserInfoDto[]>([]);
    const [viewedUserId, setViewedUserId] = useState<number | undefined>();
    const viewingSomeoneElse = viewedUserId !== undefined && viewedUserId !== auth.state.user?.id;

    const totalBytes = saves.reduce((sum, save) => sum + save.sizeBytes, 0);
    const gameCount = new Set(saves.map(groupKey)).size;

    async function reload() {
        setLoading(true);
        try {
            setSaves(viewingSomeoneElse
                ? await SaveSyncEndpoint.getSavesForUser(viewedUserId!)
                : await SaveSyncEndpoint.getMySaves());
        } finally {
            setLoading(false);
        }
    }

    async function deleteConfirmed() {
        const ids = pendingDeletion!.ids;
        setPendingDeletion(undefined);
        await SaveSyncEndpoint.deleteSaves(ids);
        await reload();
    }

    useEffect(() => {
        if (admin) UserEndpoint.getAllUsers().then(setUsers);
    }, [admin]);

    useEffect(() => {
        if (enabled) SaveSyncEndpoint.getQuotaBytes().then(setQuotaBytes);
    }, [enabled]);

    useEffect(() => {
        if (enabled) void reload();
    }, [enabled, viewedUserId]);

    const groups = useMemo(() => {
        const term = searchTerm.trim().toLowerCase();
        const matching = term ? saves.filter(s => (s.gameTitle ?? "").toLowerCase().includes(term)) : saves;
        return groupByGame(matching);
    }, [saves, searchTerm]);

    if (enabled === undefined) {
        return <div className="flex flex-col items-center justify-center h-[70vh]"><Spinner size="lg"/></div>;
    }

    if (!enabled) {
        return (
            <div className="flex flex-col items-center justify-center h-[70vh] text-center gap-4">
                <CloudSlashIcon size={64} className="text-muted"/>
                <p className="text-xl font-semibold">Cloud saves are turned off</p>
                <p className="text-muted">
                    {admin
                        ? "Enable save synchronization in Administration to let people sync their saves."
                        : "Ask an administrator to enable save synchronization."}
                </p>
            </div>
        );
    }

    return (
        <div className="flex flex-col grow gap-4">
            <h1 className="text-2xl font-bold">Cloud Saves</h1>

            {quotaBytes > 0 &&
                <div className="flex flex-col gap-1 max-w-xl">
                    <ProgressBar aria-label="Storage used"
                                 value={Math.min(100, (totalBytes / quotaBytes) * 100)}
                                 color={totalBytes >= quotaBytes ? "danger" : "accent"}>
                        <ProgressBar.Track>
                            <ProgressBar.Fill/>
                        </ProgressBar.Track>
                    </ProgressBar>
                    <p className="text-sm text-muted">
                        {humanFileSize(totalBytes)} of {humanFileSize(quotaBytes)} used,{" "}
                        {saves.length} saves across {gameCount} games
                    </p>
                </div>
            }

            <div className="flex flex-row gap-4 items-end flex-wrap">
                <SearchField className="w-96" aria-label="Search games" value={searchTerm} onChange={setSearchTerm}>
                    <SearchField.Group>
                        <SearchField.SearchIcon/>
                        <SearchField.Input placeholder="Search games"/>
                        <SearchField.ClearButton/>
                    </SearchField.Group>
                </SearchField>

                {admin &&
                    <Select className="w-64"
                            aria-label="Viewing"
                            value={viewedUserId?.toString() ?? MY_SAVES}
                            onChange={value => setViewedUserId(value && value !== MY_SAVES ? Number(value) : undefined)}>
                        <Label className="sr-only">Viewing</Label>
                        <Select.Trigger>
                            <Select.Value/>
                            <Select.Indicator/>
                        </Select.Trigger>
                        <Select.Popover>
                            <ListBox>
                                <ListBox.Item key={MY_SAVES} id={MY_SAVES} textValue="My saves">
                                    My saves
                                    <ListBox.ItemIndicator/>
                                </ListBox.Item>
                                {users.filter(user => user.id !== auth.state.user?.id).map(user =>
                                    <ListBox.Item key={user.id} id={user.id.toString()} textValue={user.username}>
                                        {user.username}
                                        <ListBox.ItemIndicator/>
                                    </ListBox.Item>
                                )}
                            </ListBox>
                        </Select.Popover>
                    </Select>
                }

                <Button variant="danger-soft"
                        isDisabled={saves.length === 0}
                        onPress={() => setPendingDeletion({
                            label: `all ${saves.length} saves across ${gameCount} games`,
                            ids: saves.map(save => save.id)
                        })}>
                    <TrashIcon/>
                    Delete all saves
                </Button>
            </div>

            {viewingSomeoneElse &&
                <p className="text-sm text-warning">
                    Viewing another user's saves.
                </p>
            }

            {loading && <Spinner size="lg"/>}

            {!loading && groups.length === 0 &&
                <div className="flex flex-col items-center justify-center h-[50vh] text-center gap-4">
                    <CloudSlashIcon size={64} className="text-muted"/>
                    <p className="text-xl font-semibold">No saves yet</p>
                    <p className="text-muted">Saves appear here once a connected app uploads them.</p>
                </div>
            }

            {!loading && groups.length > 0 &&
                <Accordion variant="surface" allowsMultipleExpanded>
                    {groups.map(group =>
                        <Accordion.Item key={group.key} id={group.key} aria-label={group.gameTitle}>
                            <Accordion.Heading>
                                <Accordion.Trigger className="flex items-center justify-between">
                                    <span className="flex flex-col items-start">
                                        <span className="font-semibold">{group.gameTitle}</span>
                                        <span className="text-sm text-muted">
                                            {group.gameId === undefined && "No longer in the library · "}
                                            {group.lastSyncedAt ? `Last synced ${timeUntil(group.lastSyncedAt)}` : "Never synced"}
                                            {" · "}{group.versions.length} versions{" · "}{humanFileSize(group.totalBytes)}
                                        </span>
                                    </span>
                                    <Accordion.Indicator/>
                                </Accordion.Trigger>
                            </Accordion.Heading>
                            <Accordion.Panel>
                                <Accordion.Body className="flex flex-col gap-2">
                                    <SaveVersionsTable saves={group.versions}
                                                       label={`Save versions for ${group.gameTitle}`}
                                                       onChange={reload}/>
                                    <Button className="self-end" size="sm" variant="danger-soft"
                                            onPress={() => setPendingDeletion({
                                                label: `all ${group.versions.length} versions of ${group.gameTitle}`,
                                                ids: group.versions.map(save => save.id)
                                            })}>
                                        <TrashIcon/>
                                        Delete all versions
                                    </Button>
                                </Accordion.Body>
                            </Accordion.Panel>
                        </Accordion.Item>
                    )}
                </Accordion>
            }

            <ConfirmModal isOpen={pendingDeletion !== undefined}
                          onOpenChange={open => !open && setPendingDeletion(undefined)}
                          title="Confirm deletion"
                          confirmLabel="Confirm deletion"
                          onConfirm={deleteConfirmed}>
                <p>Delete {pendingDeletion?.label}? Deleted saves cannot be recovered.</p>
            </ConfirmModal>
        </div>
    );
}
