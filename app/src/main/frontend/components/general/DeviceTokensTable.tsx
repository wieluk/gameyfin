import {Button, Table, toast, Tooltip} from "@heroui/react";
import {TrashIcon} from "@phosphor-icons/react";
import {useEffect, useState} from "react";
import {DeviceTokenEndpoint} from "Frontend/generated/endpoints";
import DeviceTokenDto from "Frontend/generated/org/gameyfin/app/users/devicetokens/DeviceTokenDto";
import {timeUntil} from "Frontend/util/utils";

export default function DeviceTokensTable() {
    const [tokens, setTokens] = useState<DeviceTokenDto[]>([]);

    async function load() {
        try {
            setTokens(await DeviceTokenEndpoint.getAll());
        } catch (e) {
            toast.danger("Could not load devices", {
                description: "Please try again later"
            });
        }
    }

    useEffect(() => {
        load();
    }, []);

    async function revoke(token: DeviceTokenDto) {
        try {
            await DeviceTokenEndpoint.revoke(token.id);
        } catch (e) {
            toast.danger(`Could not sign out ${token.name}`, {
                description: "Please try again later"
            });
        }
        await load();
    }

    return (
        <Table variant="secondary">
            <Table.ScrollContainer>
                <Table.Content aria-label="Signed-in devices">
                    <Table.Header>
                        <Table.Column id="name" isRowHeader>Device</Table.Column>
                        <Table.Column id="createdAt">Signed in</Table.Column>
                        <Table.Column id="lastUsedAt">Last used</Table.Column>
                        <Table.Column id="actions">Actions</Table.Column>
                    </Table.Header>
                    <Table.Body items={tokens}
                                renderEmptyState={() =>
                                    <p className="text-center text-muted p-4">No devices are signed in.</p>}>
                        {(token: DeviceTokenDto) =>
                            <Table.Row id={token.id}>
                                <Table.Cell>{token.name}</Table.Cell>
                                <Table.Cell>{timeUntil(token.createdAt)}</Table.Cell>
                                <Table.Cell>{token.lastUsedAt ? timeUntil(token.lastUsedAt) : "Never"}</Table.Cell>
                                <Table.Cell>
                                    <Tooltip>
                                        <Tooltip.Trigger>
                                            <Button isIconOnly size="sm" variant="danger"
                                                    onPress={() => revoke(token)}>
                                                <TrashIcon/>
                                            </Button>
                                        </Tooltip.Trigger>
                                        <Tooltip.Content>Sign this device out</Tooltip.Content>
                                    </Tooltip>
                                </Table.Cell>
                            </Table.Row>
                        }
                    </Table.Body>
                </Table.Content>
            </Table.ScrollContainer>
        </Table>
    );
}
