import {Button, Chip, Table, TableBody, TableCell, TableColumn, TableHeader, TableRow, Tooltip} from "@heroui/react";
import {DownloadSimpleIcon, LockKeyIcon, LockKeyOpenIcon, TrashIcon} from "@phosphor-icons/react";
import {SaveSyncEndpoint} from "Frontend/generated/endpoints";
import GameSaveDto from "Frontend/generated/org/gameyfin/app/saves/dto/GameSaveDto";
import {SaveArchiveEndpoint} from "Frontend/endpoints/endpoints";
import {humanFileSize, timeUntil} from "Frontend/util/utils";

interface SaveVersionsTableProps {
    saves: GameSaveDto[];
    label: string;
    // Hidden for an admin looking at someone else's saves, matching what the endpoint allows
    showDownload?: boolean;
    onChange: () => Promise<void> | void;
}

export default function SaveVersionsTable({saves, label, showDownload = true, onChange}: SaveVersionsTableProps) {
    async function remove(save: GameSaveDto) {
        await SaveSyncEndpoint.deleteSaves([save.id]);
        await onChange();
    }

    async function toggleLock(save: GameSaveDto) {
        await SaveSyncEndpoint.setLocked(save.id, !save.locked);
        await onChange();
    }

    return (
        <Table removeWrapper isStriped aria-label={label}>
            <TableHeader>
                <TableColumn key="createdAt">Saved</TableColumn>
                <TableColumn key="size">Size</TableColumn>
                <TableColumn key="device">Device</TableColumn>
                <TableColumn key="platform">Platform</TableColumn>
                <TableColumn key="actions" width={1}> </TableColumn>
            </TableHeader>
            <TableBody items={saves}>
                {(save: GameSaveDto) =>
                    <TableRow key={save.id}>
                        <TableCell>{save.createdAt ? timeUntil(save.createdAt) : "Unknown"}</TableCell>
                        <TableCell>{humanFileSize(save.sizeBytes)}</TableCell>
                        <TableCell>{save.deviceName ?? "Unknown device"}</TableCell>
                        <TableCell><Chip size="sm" variant="flat">{save.platform}</Chip></TableCell>
                        <TableCell>
                            <div className="flex flex-row gap-2 justify-end">
                                {showDownload &&
                                    <Tooltip content="Download this version">
                                        <Button size="sm" isIconOnly variant="light"
                                                onPress={() => SaveArchiveEndpoint.downloadSave(save.gameId, save.id)}>
                                            <DownloadSimpleIcon/>
                                        </Button>
                                    </Tooltip>
                                }
                                <Tooltip
                                    content={save.locked ? "Allow this version to be cleaned up" : "Keep this version forever"}>
                                    <Button size="sm" isIconOnly variant="light" onPress={() => toggleLock(save)}>
                                        {save.locked ? <LockKeyIcon/> : <LockKeyOpenIcon/>}
                                    </Button>
                                </Tooltip>
                                <Tooltip content="Delete this version">
                                    <Button size="sm" isIconOnly variant="light" color="danger"
                                            onPress={() => remove(save)}>
                                        <TrashIcon/>
                                    </Button>
                                </Tooltip>
                            </div>
                        </TableCell>
                    </TableRow>
                }
            </TableBody>
        </Table>
    );
}
