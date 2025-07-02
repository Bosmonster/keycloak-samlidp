import IdentityProviderRepresentation from "@keycloak/keycloak-admin-client/lib/defs/identityProviderRepresentation";

import { FormProvider, useFormContext } from "react-hook-form";
import { useTranslation } from "react-i18next";
import {
    NumberControl,
    SelectControl,
    TextControl
} from "@keycloak/keycloak-ui-shared";
import { DefaultSwitchControl } from "../../components/SwitchControl";

import "./discovery-settings.css";
import {Flex, FlexItem, Title} from "@patternfly/react-core";
import {MultiCardInput} from "../../components/multi-card-input/MultiCardInput";

type SamlExtendedSettingsProps = {};

export const SamlExtendedSettings = ({
                                    }: SamlExtendedSettingsProps) => {
    const { t } = useTranslation();

    const form = useFormContext<IdentityProviderRepresentation>();

    return (
        <div className="pf-v5-c-form pf-m-horizontal">
            <FormProvider {...form}>


                <Title headingLevel="h2" size="xl" className="kc-form-panel__title">
                    {t("Metadata")}
                </Title>

                <Flex alignItems={{ default: "alignItemsFlexEnd" }} spaceItems={{ default: "spaceItemsMd" }}>
                    <FlexItem>
                        <NumberControl
                            name="config.metadataExpiresIn"
                            label={t("metadataExpiresIn")}
                            labelIcon={t("metadataExpiresInHelp")}
                            controller={{ defaultValue: 0, rules: { min: 0, max: 2147483 } }}
                        />
                    </FlexItem>
                    <FlexItem>
                        <SelectControl
                            name="config.metadataValidUntilPeriod"
                            label={undefined}
                            controller={{ defaultValue: "DAYS" }}
                            options={[
                                { key: "DAYS", value: t("days") },
                                { key: "WEEKS", value: t("weeks") },
                                { key: "MONTHS", value: t("months") },
                                { key: "YEARS", value: t("years") },
                            ]}
                        />
                    </FlexItem>
                </Flex>

                <TextControl
                    name="config.linkedProviders"
                    label={t("linkedProviders")}
                    labelIcon={t("linkedProvidersHelp")}
                />

                <Title headingLevel="h2" size="xl" className="kc-form-panel__title">
                    {t("artifactResolution")}
                </Title>

                <DefaultSwitchControl
                    name="config.artifactResolution"
                    label={t("artifactResolution")}
                    labelIcon={t("artifactResolutionHelp")}
                    stringify
                />

                <TextControl
                    name="config.artifactResolutionEndpoint"
                    label={t("artifactResolutionEndpoint")}
                    labelIcon={t("artifactResolutionEndpointHelp")}
                    type="url"
                />

                <DefaultSwitchControl
                    name="config.artifactResolutionServiceInMetadata"
                    label={t("includeArtifactResolutionServiceMetadata")}
                    labelIcon={t("includeArtifactResolutionServiceMetadataHelp")}
                    stringify
                />

                <DefaultSwitchControl
                    name="config.signArtifactResolutionRequest"
                    label={t("signArtifactResolutionRequest")}
                    labelIcon={t("signArtifactResolutionRequestHelp")}
                    stringify
                />

                <DefaultSwitchControl
                    name="config.artifactResolutionHttpArtifact"
                    label={t("artifactResolutionHttpArtifact")}
                    labelIcon={t("artifactResolutionHttpArtifactHelp")}
                    stringify
                />

                <DefaultSwitchControl
                    name="config.artifactResolutionSoap"
                    label={t("artifactResolutionSoap")}
                    labelIcon={t("artifactResolutionSoapHelp")}
                    stringify
                />

                <DefaultSwitchControl
                    name="config.artifactResolutionXmlHeader"
                    label={t("artifactResolutionXmlHeader")}
                    labelIcon={t("artifactResolutionXmlHeaderHelp")}
                    stringify
                />

                <SelectControl
                    name="config.charSet"
                    label={t("charSet")}
                    labelIcon={t("charSetHelp")}
                    controller={{
                        defaultValue:
                            "UTF-8",
                    }}
                    options={[
                        {
                            key: "UTF-8",
                            value: t("UTF-8"),
                        },
                        {
                            key: "UTF-16BE",
                            value: t("UTF-16BE"),
                        },
                        {
                            key: "UTF-16LE",
                            value: t("UTF-16LE"),
                        },
                        {
                            key: "UTF-16",
                            value: t("UTF-16"),
                        },
                    ]}
                />

                <DefaultSwitchControl
                    name="config.artifactResolutionMutualTls"
                    label={t("artifactResolutionMutualTls")}
                    labelIcon={t("artifactResolutionMutualTlsHelp")}
                    stringify
                />


                <Title headingLevel="h2" size="xl" className="kc-form-panel__title">
                    {t("attributeServices")}
                </Title>

                <MultiCardInput
                    name="config.attributeService"
                    fieldKeys={[
                        { name: "serviceName", label: t("asServiceName") },
                        { name: "friendlyName", label: t("asFriendlyName") },
                        { name: "attributeName", label: t("asAttributeName") },
                        { name: "attributeValue", label: t("asAttributeValue") }
                    ]}
                    requiredFields={["serviceName", "friendlyName","attributeValue"]}
                    stringify
                />

            </FormProvider>
        </div>
    );
};

