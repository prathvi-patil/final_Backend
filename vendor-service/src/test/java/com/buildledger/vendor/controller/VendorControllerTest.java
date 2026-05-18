package com.buildledger.vendor.controller;

import com.buildledger.vendor.dto.request.CreateVendorRequestDTO;
import com.buildledger.vendor.dto.request.UpdateVendorRequestDTO;
import com.buildledger.vendor.dto.request.VendorLoginRequestDTO;
import com.buildledger.vendor.dto.response.VendorDocumentResponseDTO;
import com.buildledger.vendor.dto.response.VendorLoginResponseDTO;
import com.buildledger.vendor.dto.response.VendorResponseDTO;
import com.buildledger.vendor.enums.DocumentType;
import com.buildledger.vendor.enums.VendorStatus;
import com.buildledger.vendor.enums.VerificationStatus;
import com.buildledger.vendor.exception.GlobalExceptionHandler;
import com.buildledger.vendor.exception.ResourceNotFoundException;
import com.buildledger.vendor.service.VendorAuthService;
import com.buildledger.vendor.service.VendorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests using standalone MockMvc (no Spring context loaded).
 * Security filters are not applied — the focus is HTTP mapping, status codes,
 * and JSON response structure.
 */
@ExtendWith(MockitoExtension.class)
class VendorControllerTest {

    @Mock private VendorService vendorService;
    @Mock private VendorAuthService vendorAuthService;

    @InjectMocks
    private VendorController vendorController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(vendorController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // Registers JavaTimeModule for LocalDateTime serialization
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private VendorResponseDTO buildVendorResponse(Long id, VendorStatus status) {
        return VendorResponseDTO.builder()
                .vendorId(id)
                .name("Test Vendor")
                .email("vendor@example.com")
                .username("testvendor")
                .status(status)
                .build();
    }

    private VendorDocumentResponseDTO buildDocumentResponse() {
        return VendorDocumentResponseDTO.builder()
                .documentId(10L)
                .vendorId(1L)
                .vendorName("Test Vendor")
                .docType(DocumentType.PAN_CARD)
                .verificationStatus(VerificationStatus.PENDING)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POST /vendors/auth/login
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /vendors/auth/login — 200: returns JWT token")
    void loginPendingVendor_success() throws Exception {
        VendorLoginRequestDTO request = new VendorLoginRequestDTO();
        request.setUsername("testvendor");
        request.setPassword("Test@1234");

        VendorLoginResponseDTO loginResponse = VendorLoginResponseDTO.builder()
                .accessToken("mock.jwt.token")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .vendorId(1L)
                .username("testvendor")
                .status(VendorStatus.PENDING)
                .build();

        when(vendorAuthService.loginPendingVendor(any(VendorLoginRequestDTO.class)))
                .thenReturn(loginResponse);

        mockMvc.perform(post("/vendors/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POST /vendors/register
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /vendors/register — 201: returns registered vendor with PENDING status")
    void registerVendor_success() throws Exception {
        CreateVendorRequestDTO request = new CreateVendorRequestDTO();
        request.setName("Test Vendor");
        request.setEmail("vendor@example.com");
        request.setUsername("testvendor");
        request.setPassword("Test@1234!");
        request.setCategory("Technology");

        VendorResponseDTO vendorResponse = buildVendorResponse(1L, VendorStatus.PENDING);
        when(vendorService.registerVendor(any(CreateVendorRequestDTO.class))).thenReturn(vendorResponse);

        mockMvc.perform(post("/vendors/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vendorId").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET /vendors
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /vendors — 200: returns list of all vendors")
    void getAllVendors_success() throws Exception {
        when(vendorService.getAllVendors()).thenReturn(
                List.of(buildVendorResponse(1L, VendorStatus.PENDING),
                        buildVendorResponse(2L, VendorStatus.ACTIVE)));

        mockMvc.perform(get("/vendors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET /vendors/{vendorId}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /vendors/{vendorId} — 200: returns vendor by ID")
    void getVendorById_success() throws Exception {
        when(vendorService.getVendorById(1L)).thenReturn(buildVendorResponse(1L, VendorStatus.ACTIVE));

        mockMvc.perform(get("/vendors/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendorId").value(1))
                .andExpect(jsonPath("$.data.name").value("Test Vendor"));
    }

    @Test
    @DisplayName("GET /vendors/{vendorId} — 404: returns error when vendor not found")
    void getVendorById_notFound_returns404() throws Exception {
        when(vendorService.getVendorById(99L))
                .thenThrow(new ResourceNotFoundException("Vendor", "id", 99L));

        mockMvc.perform(get("/vendors/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET /vendors/username/{username}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /vendors/username/{username} — 200: returns vendor by username")
    void getVendorByUsername_success() throws Exception {
        when(vendorService.getVendorByUsername("testvendor"))
                .thenReturn(buildVendorResponse(1L, VendorStatus.ACTIVE));

        mockMvc.perform(get("/vendors/username/testvendor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("testvendor"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET /vendors/status/{status}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /vendors/status/{status} — 200: returns vendors filtered by status")
    void getVendorsByStatus_success() throws Exception {
        when(vendorService.getVendorsByStatus(VendorStatus.PENDING))
                .thenReturn(List.of(buildVendorResponse(1L, VendorStatus.PENDING)));

        mockMvc.perform(get("/vendors/status/PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUT /vendors/{vendorId}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PUT /vendors/{vendorId} — 200: returns updated vendor")
    void updateVendor_success() throws Exception {
        UpdateVendorRequestDTO request = new UpdateVendorRequestDTO();
        request.setName("Updated Name");

        VendorResponseDTO updated = VendorResponseDTO.builder()
                .vendorId(1L).name("Updated Name").status(VendorStatus.ACTIVE).build();

        when(vendorService.updateVendor(eq(1L), any(UpdateVendorRequestDTO.class))).thenReturn(updated);

        mockMvc.perform(put("/vendors/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Updated Name"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DELETE /vendors/{vendorId}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DELETE /vendors/{vendorId} — 200: vendor deleted successfully")
    void deleteVendor_success() throws Exception {
        doNothing().when(vendorService).deleteVendor(1L);

        mockMvc.perform(delete("/vendors/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Vendor deleted successfully"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POST /vendors/{vendorId}/documents
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /vendors/{vendorId}/documents — 201: document uploaded successfully")
    void uploadDocument_success() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF content".getBytes());

        when(vendorService.uploadDocument(anyLong(), any(), any(DocumentType.class),
                any(), anyString())).thenReturn(buildDocumentResponse());

        mockMvc.perform(multipart("/vendors/1/documents")
                        .file(file)
                        .param("docType", "PAN_CARD")
                        .param("remarks", "Test upload"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.documentId").value(10))
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET /vendors/{vendorId}/documents
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /vendors/{vendorId}/documents — 200: returns document for vendor")
    void getVendorDocument_success() throws Exception {
        when(vendorService.getVendorDocument(1L)).thenReturn(buildDocumentResponse());

        mockMvc.perform(get("/vendors/1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(10))
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUT /vendors/documents/{documentId}/review
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PUT /vendors/documents/{documentId}/review — 200: document approved")
    void reviewDocument_approved_success() throws Exception {
        VendorDocumentResponseDTO approvedDoc = VendorDocumentResponseDTO.builder()
                .documentId(10L).vendorId(1L)
                .verificationStatus(VerificationStatus.APPROVED)
                .reviewedBy("admin")
                .build();

        when(vendorService.reviewDocument(eq(10L), eq(VerificationStatus.APPROVED),
                any(), eq("admin"))).thenReturn(approvedDoc);

        mockMvc.perform(put("/vendors/documents/10/review")
                        .param("status", "APPROVED")
                        .param("reviewRemarks", "All documents look good")
                        .header("X-Username", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.message").value("Document approved successfully."));
    }

    @Test
    @DisplayName("PUT /vendors/documents/{documentId}/review — 200: document rejected")
    void reviewDocument_rejected_success() throws Exception {
        VendorDocumentResponseDTO rejectedDoc = VendorDocumentResponseDTO.builder()
                .documentId(10L).vendorId(1L)
                .verificationStatus(VerificationStatus.REJECTED)
                .reviewedBy("admin")
                .build();

        when(vendorService.reviewDocument(eq(10L), eq(VerificationStatus.REJECTED),
                any(), eq("admin"))).thenReturn(rejectedDoc);

        mockMvc.perform(put("/vendors/documents/10/review")
                        .param("status", "REJECTED")
                        .param("reviewRemarks", "Invalid document")
                        .header("X-Username", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("Document rejected."));
    }
}
