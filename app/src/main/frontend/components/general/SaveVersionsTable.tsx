import {Button, Chip, Table, Tooltip} from "@heroui/react";
import {DownloadSimpleIcon, LockKeyIcon, LockKeyOpenIcon, TrashIcon} from "@phosphor-icons/react";
import {SaveSyncEndpoint} from "Frontend/generated/endpoints";
import GameSaveDto from "Frontend/generated/org/gameyfin/app/saves/dto/GameSaveDto";
import {SaveArchiveEndpoint} from "Frontend/endpoints/endpoints";
import {humanFileSize, timeUntil} from "Frontend/util/utils";

interface SaveVersionsTableProps {
    saves: GameSaveDto[];
    label: string;
    onChange: () => Promise<void> | void;
}

export default function SaveVersionsTable({saves, label, onChange}: SaveVersionsTableProps) {
    async function remove(save: GameSaveDto) {
        await SaveSyncEndpoint.deleteSaves([save.id]);
        await onChange();
    }

    async function toggleLock(save: GameSaveDto) {
        await SaveSyncEndpoint.setLocked(save.id, !save.locked);
        await onChange();
    }

    return (
        <Table variant="secondary">
            <Table.ScrollContainer>
                <Table.Content aria-label={label}>
                    <Table.Header>
                        <Table.Column id="createdAt" isRowHeader>Saved</Table.Column>
                        <Table.Column id="size">Size</Table.Column>
                        <Table.Column id="device">Device</Table.Column>
                        <Table.Column id="platform">Platform</Table.Column>
                        <Table.Column id="actions">Actions</Table.Column>
                    </Table.Header>
                    <Table.Body items={saves}>
                        {(save: GameSaveDto) =>
                            <Table.Row id={save.id}>
                                <Table.Cell>{save.createdAt ? timeUntil(save.createdAt) : "Unknown"}</Table.Cell>
                                <Table.Cell>{humanFileSize(save.sizeBytes)}</Table.Cell>
                                <Table.Cell>{save.deviceName ?? "Unknown device"}</Table.Cell>
                                <Table.Cell><Chip size="sm" variant="secondary">{save.platform}</Chip></Table.Cell>
                                <Table.Cell>
                                    <div className="flex flex-row gap-2">
                                        <Tooltip>
                                            <Tooltip.Trigger>
                                                <Button isIconOnly size="sm" variant="ghost"
                                                        onPress={() => SaveArchiveEndpoint.downloadSave(save.id)}>
                                                    <DownloadSimpleIcon/>
                                                </Button>
                                            </Tooltip.Trigger>
                                            <Tooltip.Content>Download this version</Tooltip.Content>
                                        </Tooltip>
                                        <Tooltip>
                                            <Tooltip.Trigger>
                                                <Button isIconOnly size="sm" variant="ghost"
                                                        onPress={() => toggleLock(save)}>
                                                    {save.locked ? <LockKeyIcon/> : <LockKeyOpenIcon/>}
                                                </Button>
                                            </Tooltip.Trigger>
                                            <Tooltip.Content>
                                                {save.locked ? "Allow this version to be cleaned up" : "Keep this version forever"}
                                            </Tooltip.Content>
                                        </Tooltip>
                                        <Tooltip>
                                            <Tooltip.Trigger>
                                                <Button isIconOnly size="sm" variant="danger"
                                                        onPress={() => remove(save)}>
                                                    <TrashIcon/>
                                                </Button>
                                            </Tooltip.Trigger>
                                            <Tooltip.Content>Delete this version</Tooltip.Content>
                                        </Tooltip>
                                    </div>
                                </Table.Cell>
                            </Table.Row>
                        }
                    </Table.Body>
                </Table.Content>
            </Table.ScrollContainer>
        </Table>
    );
}
