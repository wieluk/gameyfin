import {useEffect, useState} from "react";
import {SaveSyncEndpoint} from "Frontend/generated/endpoints";
import {useAuth} from "Frontend/util/auth";

// Undefined while loading. The endpoint needs a session, so anonymous visitors never call it.
export function useSaveSyncEnabled(): boolean | undefined {
    const auth = useAuth();
    const [enabled, setEnabled] = useState<boolean | undefined>();

    useEffect(() => {
        if (!auth.state.user) {
            setEnabled(false);
            return;
        }
        SaveSyncEndpoint.isEnabled().then(setEnabled).catch(() => setEnabled(false));
    }, [auth.state.user]);

    return enabled;
}
