import withConfigPage from "Frontend/components/administration/withConfigPage";
import * as Yup from 'yup';
import ConfigFormField from "Frontend/components/administration/ConfigFormField";
import Section from "Frontend/components/general/Section";
import {Button} from "@heroui/react";
import {useNavigate} from "react-router";

function SaveSyncManagementLayout({getConfig, formik}: any) {
    const navigate = useNavigate();
    const enabled = formik.values["save-sync"]?.enabled;

    return (
        <div className="flex flex-col">
            <Section title="Save synchronization"/>
            <ConfigFormField configElement={getConfig("save-sync.enabled")}/>

            <Section title="Limits"/>
            <div className="flex flex-row items-center gap-4">
                <ConfigFormField configElement={getConfig("save-sync.max-size-mb")}
                                 isDisabled={!enabled}/>
                <ConfigFormField configElement={getConfig("save-sync.max-total-per-user-mb")}
                                 isDisabled={!enabled}/>
            </div>
            <ConfigFormField configElement={getConfig("save-sync.max-versions-per-game")}
                             isDisabled={!enabled}/>

            <Button className="mt-4" onPress={() => navigate("/cloud-saves")}>
                View cloud saves
            </Button>
        </div>
    );
}

const validationSchema = Yup.object({
    "save-sync": Yup.object({
        enabled: Yup.boolean().required("Required"),
        "max-size-mb": Yup.number()
            .min(1, "Must be at least 1")
            .required("Required"),
        "max-versions-per-game": Yup.number()
            .min(1, "Must be at least 1")
            .required("Required"),
        "max-total-per-user-mb": Yup.number()
            .min(1, "Must be at least 1")
            .required("Required"),
    }).required("Required"),
});

export const SaveSyncManagement = withConfigPage(SaveSyncManagementLayout, "Save Sync", validationSchema);
