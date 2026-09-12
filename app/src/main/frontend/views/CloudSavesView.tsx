import {useEffect, useMemo, useState} from "react";
import {
    Accordion,
    AccordionItem,
    Autocomplete,
    AutocompleteItem,
    Button,
    Input,
    Modal,
    ModalBody,
    ModalContent,
    ModalFooter,
    ModalHeader,
    Progress,
    Spinner
} from "@heroui/react";
import {CloudSlashIcon, TrashIcon} from "@phosphor-icons/react";
import {SaveSyncEndpoint, UserEndpoint} from "Frontend/generated/endpoints";
import GameSaveDto from "Frontend/generated/org/gameyfin/app/saves/dto/GameSaveDto";
import ExtendedUserInfoDto from "Frontend/generated/org/gameyfin/app/users/dto/ExtendedUserInfoDto";
import SaveVersionsTable from "Frontend/components/general/SaveVersionsTable";
import {humanFileSize, isAdmin, timeUntil} from "Frontend/util/utils";
import {useAuth} from "Frontend/util/auth";
import {useSaveSyncEnabled} from "Frontend/util/saveSync";

interface GameGroup {
    gameId: number;
    gameTitle: string;
    versions: GameSaveDto[];
    totalBytes: number;
    lastSyncedAt?: string;
}

function groupByGame(saves: GameSaveDto[]): GameGroup[] {
    const groups = new Map<number, GameGroup>();

    for (const save of saves) {
        const group = groups.get(save.gameId) ?? {
            gameId: save.gameId,
            gameTitle: save.gameTitle ?? "Unknown game",
            versions: [],
            totalBytes: 0,
            // The endpoint returns newest first, so a game's first version is its latest sync
            lastSyncedAt: save.createdAt
        };
        group.versions.push(save);
        group.totalBytes += save.sizeBytes;
        groups.set(save.gameId, group);
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
    const gameCount = new Set(saves.map(save => save.gameId)).size;

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
                <CloudSlashIcon size={64} className="text-default-300"/>
                <p className="text-xl font-semibold text-default-600">Cloud saves are turned off</p>
                <p className="text-default-500">
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
                    <Progress
                        aria-label="Storage used"
                        value={Math.min(100, (totalBytes / quotaBytes) * 100)}
                        color={totalBytes >= quotaBytes ? "danger" : "primary"}
                    />
                    <p className="text-sm text-default-500">
                        {humanFileSize(totalBytes)} of {humanFileSize(quotaBytes)} used,{" "}
                        {saves.length} saves across {gameCount} games
                    </p>
                </div>
            }

            <div className="flex flex-row gap-4 items-end flex-wrap">
                <Input className="w-96" isClearable placeholder="Search games"
                       value={searchTerm}
                       onChange={e => setSearchTerm(e.target.value)}
                       onClear={() => setSearchTerm("")}/>

                {admin &&
                    <Autocomplete className="w-64"
                                  label="Viewing"
                                  size="sm"
                                  selectedKey={viewedUserId?.toString()}
                                  onSelectionChange={key => setViewedUserId(key ? Number(key) : undefined)}>
                        {users.map(user =>
                            <AutocompleteItem key={user.id.toString()}>{user.username}</AutocompleteItem>
                        )}
                    </Autocomplete>
                }

                <Button color="danger" variant="flat" startContent={<TrashIcon/>}
                        isDisabled={saves.length === 0}
                        onPress={() => setPendingDeletion({
                            label: `all ${saves.length} saves across ${gameCount} games`,
                            ids: saves.map(save => save.id)
                        })}>
                    Delete all saves
                </Button>
            </div>

            {viewingSomeoneElse &&
                <p className="text-sm text-warning-600">
                    Viewing another user's saves. You can delete them, but not download them.
                </p>
            }

            {loading && <Spinner size="lg"/>}

            {!loading && groups.length === 0 &&
                <div className="flex flex-col items-center justify-center h-[50vh] text-center gap-4">
                    <CloudSlashIcon size={64} className="text-default-300"/>
                    <p className="text-xl font-semibold text-default-600">No saves yet</p>
                    <p className="text-default-500">Saves appear here once a connected app uploads them.</p>
                </div>
            }

            {!loading && groups.length > 0 &&
                <Accordion variant="splitted" selectionMode="multiple">
                    {groups.map(group =>
                        <AccordionItem
                            key={group.gameId.toString()}
                            aria-label={group.gameTitle}
                            title={<span className="font-semibold">{group.gameTitle}</span>}
                            subtitle={
                                <span className="text-sm text-default-500">
                                    {group.lastSyncedAt ? `Last synced ${timeUntil(group.lastSyncedAt)}` : "Never synced"}
                                    {" · "}{group.versions.length} versions{" · "}{humanFileSize(group.totalBytes)}
                                </span>
                            }>
                            <div className="flex flex-col gap-2">
                                <SaveVersionsTable saves={group.versions}
                                                   label={`Save versions for ${group.gameTitle}`}
                                                   showDownload={!viewingSomeoneElse}
                                                   onChange={reload}/>
                                <Button className="self-end" size="sm" color="danger" variant="light"
                                        startContent={<TrashIcon/>}
                                        onPress={() => setPendingDeletion({
                                            label: `all ${group.versions.length} versions of ${group.gameTitle}`,
                                            ids: group.versions.map(save => save.id)
                                        })}>
                                    Delete all versions
                                </Button>
                            </div>
                        </AccordionItem>
                    )}
                </Accordion>
            }

            <Modal isOpen={pendingDeletion !== undefined} onOpenChange={() => setPendingDeletion(undefined)}
                   backdrop="opaque" isDismissable={false} size="lg">
                <ModalContent>
                    <ModalHeader>Confirm deletion</ModalHeader>
                    <ModalBody>
                        <p>Delete {pendingDeletion?.label}? Deleted saves cannot be recovered.</p>
                    </ModalBody>
                    <ModalFooter>
                        <Button variant="light" onPress={() => setPendingDeletion(undefined)}>Cancel</Button>
                        <Button color="danger" onPress={deleteConfirmed}>Confirm deletion</Button>
                    </ModalFooter>
                </ModalContent>
            </Modal>
        </div>
    );
}
