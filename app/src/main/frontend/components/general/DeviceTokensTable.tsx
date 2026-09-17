import {
    addToast,
    Button,
    Table,
    TableBody,
    TableCell,
    TableColumn,
    TableHeader,
    TableRow,
    Tooltip
} from "@heroui/react";
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
            addToast({
                title: "Could not load devices",
                description: "Please try again later",
                color: "danger"
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
            addToast({
                title: `Could not sign out ${token.name}`,
                description: "Please try again later",
                color: "danger"
            });
        }
        await load();
    }

    return (
        <Table removeWrapper isStriped aria-label="Signed-in devices">
            <TableHeader>
                <TableColumn key="name">Device</TableColumn>
                <TableColumn key="createdAt">Signed in</TableColumn>
                <TableColumn key="lastUsedAt">Last used</TableColumn>
                <TableColumn key="actions" width={1}> </TableColumn>
            </TableHeader>
            <TableBody items={tokens} emptyContent="No devices are signed in.">
                {(token: DeviceTokenDto) =>
                    <TableRow key={token.id}>
                        <TableCell>{token.name}</TableCell>
                        <TableCell>{timeUntil(token.createdAt)}</TableCell>
                        <TableCell>{token.lastUsedAt ? timeUntil(token.lastUsedAt) : "Never"}</TableCell>
                        <TableCell>
                            <Tooltip content="Sign this device out">
                                <Button size="sm" isIconOnly variant="light" color="danger"
                                        onPress={() => revoke(token)}>
                                    <TrashIcon/>
                                </Button>
                            </Tooltip>
                        </TableCell>
                    </TableRow>
                }
            </TableBody>
        </Table>
    );
}
