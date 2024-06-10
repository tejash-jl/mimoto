package io.mosip.mimoto.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;
import com.itextpdf.kernel.pdf.PdfWriter;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.mimoto.dto.IssuerDTO;
import io.mosip.mimoto.dto.IssuersDTO;
import io.mosip.mimoto.dto.mimoto.*;
import io.mosip.mimoto.exception.ApiNotAccessibleException;
import io.mosip.mimoto.exception.InvalidIssuerIdException;
import io.mosip.mimoto.service.IssuersService;
import io.mosip.mimoto.util.*;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.security.PublicKey;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class IssuersServiceImpl implements IssuersService {
    private final Logger logger = LoggerUtil.getLogger(IssuersServiceImpl.class);

    private static final String context = "https://www.w3.org/2018/credentials/v1";

    @Autowired
    private Utilities utilities;

    @Autowired
    private RestApiClient restApiClient;

    @Autowired
    private JoseUtil joseUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mosip.oidc.p12.filename}")
    private String fileName;

    @Value("${mosip.oidc.p12.password}")
    private String cyptoPassword;

    @Value("${mosip.oidc.p12.path}")
    String keyStorePath;

    @Override
    public IssuersDTO getAllIssuers(String search) throws ApiNotAccessibleException, IOException {
        IssuersDTO issuers;
        String issuersConfigJsonValue = utilities.getIssuersConfigJsonValue();
        if (issuersConfigJsonValue == null) {
            throw new ApiNotAccessibleException();
        }
        Gson gsonWithIssuerDataOnlyFilter = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        issuers = gsonWithIssuerDataOnlyFilter.fromJson(issuersConfigJsonValue, IssuersDTO.class);
        List<IssuerDTO> enabledIssuers = issuers.getIssuers().stream()
                .filter(issuer -> "true".equals(issuer.getEnabled()))
                .collect(Collectors.toList());
        issuers.setIssuers(enabledIssuers);

        // Filter issuers list with search string
        if (!StringUtils.isEmpty(search)) {
            List<IssuerDTO> filteredIssuers = issuers.getIssuers().stream()
                    .filter(issuer -> issuer.getDisplay().stream()
                            .anyMatch(displayDTO -> displayDTO.getTitle().toLowerCase().contains(search.toLowerCase())))
                    .collect(Collectors.toList());
            issuers.setIssuers(filteredIssuers);
            return issuers;
        }
        return issuers;
    }

    @Override
    public IssuersDTO getAllIssuersWithAllFields() throws ApiNotAccessibleException, IOException {
        IssuersDTO issuers;
        String issuersConfigJsonValue = utilities.getIssuersConfigJsonValue();
        if (issuersConfigJsonValue == null) {
            throw new ApiNotAccessibleException();
        }
        Gson gsonWithIssuerDataOnlyFilter = new GsonBuilder().create();
        issuers = gsonWithIssuerDataOnlyFilter.fromJson(issuersConfigJsonValue, IssuersDTO.class);

        return issuers;
    }


    @Override
    public IssuerDTO getIssuerConfig(String issuerId) throws ApiNotAccessibleException, IOException {
        IssuerDTO issuerDTO = null;
        String issuersConfigJsonValue = utilities.getIssuersConfigJsonValue();
        if (issuersConfigJsonValue == null) {
            throw new ApiNotAccessibleException();
        }
        IssuersDTO issuers = new Gson().fromJson(issuersConfigJsonValue, IssuersDTO.class);
        Optional<IssuerDTO> issuerConfigResp = issuers.getIssuers().stream()
                .filter(issuer -> issuer.getCredential_issuer().equals(issuerId))
                .findFirst();
        if (issuerConfigResp.isPresent())
            issuerDTO = issuerConfigResp.get();
        else
            throw new InvalidIssuerIdException();
        return issuerDTO;
    }

    @Override
    public IssuerSupportedCredentialsResponse getCredentialsSupported(String issuerId, String search) throws ApiNotAccessibleException, IOException {
        IssuerSupportedCredentialsResponse credentialTypesWithAuthorizationEndpoint = new IssuerSupportedCredentialsResponse();

        IssuersDTO issuersDto = getAllIssuersWithAllFields();

        Optional<IssuerDTO> issuerConfigResp = issuersDto.getIssuers().stream()
                .filter(issuer -> issuer.getCredential_issuer().equals(issuerId))
                .findFirst();
        if (issuerConfigResp.isPresent()) {
            IssuerDTO issuerDto = issuerConfigResp.get();

            // Get credential supported types from well known endpoint
            CredentialIssuerWellKnownResponse response = restApiClient.getApi(issuerDto.getWellKnownEndpoint(), CredentialIssuerWellKnownResponse.class);
            if (response == null) {
                throw new ApiNotAccessibleException();
            }
            List<CredentialsSupportedResponse> issuerCredentialsSupported = response.getCredentialsSupported();
            credentialTypesWithAuthorizationEndpoint.setAuthorizationEndPoint(issuerDto.getAuthorization_endpoint());
            credentialTypesWithAuthorizationEndpoint.setSupportedCredentials(issuerCredentialsSupported);

            // Filter Credential supported types with search string
            if (!StringUtils.isEmpty(search)) {
                credentialTypesWithAuthorizationEndpoint.setSupportedCredentials(issuerCredentialsSupported
                        .stream()
                        .filter(credentialsSupportedResponse -> credentialsSupportedResponse.getDisplay().stream()
                                .anyMatch(credDisplay -> credDisplay.getName().toLowerCase().contains(search.toLowerCase())))
                        .collect(Collectors.toList()));
            }
            return credentialTypesWithAuthorizationEndpoint;
        }
        return credentialTypesWithAuthorizationEndpoint;
    }

    @Override
    public ByteArrayInputStream generatePdfForVerifiableCredentials(String accessToken, IssuerDTO issuerDTO, CredentialsSupportedResponse credentialsSupportedResponse, String credentialEndPoint) throws Exception {
        LinkedHashMap<String, String> vcPropertiesFromWellKnown = new LinkedHashMap<>();
        Map<String, CredentialDisplayResponseDto> credentialSubject = credentialsSupportedResponse.getCredentialDefinition().getCredentialSubject();
        //populating display properties from credential Types json for pdf
        credentialSubject.keySet().forEach(VCProperty -> vcPropertiesFromWellKnown.put(VCProperty, credentialSubject.get(VCProperty).getDisplay().get(0).getName()));
        String backgroundColor = credentialsSupportedResponse.getDisplay().get(0).getBackgroundColor();
        String textColor = credentialsSupportedResponse.getDisplay().get(0).getTextColor();
        VCCredentialRequest vcCredentialRequest = generateVCCredentialRequest(issuerDTO, credentialsSupportedResponse, accessToken);
        logger.debug("VC Credential Request is -> " + vcCredentialRequest);
        //Esignet API call for credential issue
        VCCredentialResponse vcCredentialResponse = restApiClient.postApi(credentialEndPoint, MediaType.APPLICATION_JSON,
                vcCredentialRequest, VCCredentialResponse.class, accessToken);
        logger.debug("VC Credential Response is -> " + vcCredentialResponse);
        if (vcCredentialResponse == null) throw new RuntimeException("VC Credential Issue API not accessible");
        Map<String, Object> credentialProperties = vcCredentialResponse.getCredential().getCredentialSubject();
        LinkedHashMap<String, Object> displayProperties = new LinkedHashMap<>();
        String base64QRCode = generateBase64QRCode(vcCredentialResponse.getCredential());
        vcPropertiesFromWellKnown.keySet().forEach(vcProperty -> displayProperties.put(vcPropertiesFromWellKnown.get(vcProperty), credentialProperties.get(vcProperty)));
        return getPdfResourceFromVcProperties(displayProperties, textColor, backgroundColor,
                credentialsSupportedResponse.getDisplay().get(0).getName(),
                issuerDTO.getDisplay().stream().map(d -> d.getLogo().getUrl()).findFirst().orElse(""),
                base64QRCode);
    }

    private String generateBase64QRCode(@Valid @NotNull VCCredentialProperties credential) {
        int imageSize = 200;
        try {
            String data = objectMapper.writeValueAsString(credential);
            byte[] compressedData = CompressionUtils.compress(data.getBytes());
            String encodedData = Base45Util.encode(compressedData);
            BitMatrix matrix = new MultiFormatWriter().encode(encodedData, BarcodeFormat.QR_CODE,
                    imageSize, imageSize);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "png", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray()); // base64 encode
        } catch (Exception e) {
            logger.error("Exception while generating qr code", e);
        }
        return "";
    }

    private VCCredentialRequest generateVCCredentialRequest(IssuerDTO issuerDTO, CredentialsSupportedResponse credentialsSupportedResponse, String accessToken) throws ParseException {
        //Getting public key from the p12 file
        PublicKey publicKeyString = joseUtil.getPublicKeyString(keyStorePath, fileName, issuerDTO.getClient_alias(), cyptoPassword);
        //Generating proof from the public key with custom header
        String jwt = joseUtil.generateJwt(publicKeyString, keyStorePath, fileName, issuerDTO.getClient_alias(),
                cyptoPassword, issuerDTO.getCredential_audience(), issuerDTO.getClient_id(), accessToken);
        return VCCredentialRequest.builder()
                .format(credentialsSupportedResponse.getFormat())
                .proof(VCCredentialRequestProof.builder()
                        .proofType(credentialsSupportedResponse.getProofTypesSupported().get(0))
                        .jwt(jwt)
                        .build())
                .credentialDefinition(VCCredentialDefinition.builder()
                        .type(credentialsSupportedResponse.getCredentialDefinition().getType())
                        .context(List.of(context))
                        .build())
                .build();
    }

    private ByteArrayInputStream getPdfResourceFromVcProperties(LinkedHashMap<String, Object> displayProperties, String textColor,
                                                                String backgroundColor,
                                                                String credentialSupportedType, String issuerLogoUrl, String base64QRCode) throws IOException {
        Map<String, Object> data = new HashMap<>();
        LinkedHashMap<String, Object> headerProperties = new LinkedHashMap<>();
        LinkedHashMap<String, Object> rowProperties = new LinkedHashMap<>();

        //Assigning two properties at the top of pdf and the rest dynamically below them
        displayProperties.entrySet().stream()
                .forEachOrdered(entry -> {
                    if (headerProperties.size() < 2) {
                        headerProperties.put(entry.getKey(), entry.getValue());
                    } else {
                        rowProperties.put(entry.getKey(), entry.getValue());
                    }
                });

        int rowPropertiesCount = rowProperties.size();
        data.put("logoUrl", issuerLogoUrl);
        data.put("headerProperties", headerProperties);
        data.put("rowProperties", rowProperties);
        data.put("keyFontColor", textColor);
        data.put("bgColor", backgroundColor);
        data.put("rowPropertiesMargin", rowPropertiesCount % 2 == 0 ? (rowPropertiesCount / 2 - 1) * 40 : (rowPropertiesCount / 2) * 40); //for adjusting the height in pdf for dynamic properties
        data.put("titleName", credentialSupportedType);
        data.put("base64QRCode", base64QRCode);
        String credentialTemplate = utilities.getCredentialSupportedTemplateString();

        Properties props = new Properties();
        props.setProperty("resource.loader", "class");
        props.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        Velocity.init(props);
        VelocityContext velocityContext = new VelocityContext(data);

        // Merge the context with the template
        StringWriter writer = new StringWriter();
        Velocity.evaluate(velocityContext, writer, "Credential Template", credentialTemplate);

        // Get the merged HTML string
        String mergedHtml = writer.toString();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        PdfWriter pdfwriter = new PdfWriter(outputStream);
        DefaultFontProvider defaultFont = new DefaultFontProvider(true, false, false);
        ConverterProperties converterProperties = new ConverterProperties();
        converterProperties.setFontProvider(defaultFont);
        HtmlConverter.convertToPdf(mergedHtml, pdfwriter, converterProperties);
        return new ByteArrayInputStream(outputStream.toByteArray());
    }
}
