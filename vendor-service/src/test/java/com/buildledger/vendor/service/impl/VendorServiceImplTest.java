package com.buildledger.vendor.service.impl;

import com.buildledger.vendor.dto.request.CreateVendorRequestDTO;
import com.buildledger.vendor.dto.request.UpdateVendorRequestDTO;
import com.buildledger.vendor.dto.response.ApiResponseDTO;
import com.buildledger.vendor.dto.response.VendorDocumentResponseDTO;
import com.buildledger.vendor.dto.response.VendorResponseDTO;
import com.buildledger.vendor.entity.Vendor;
import com.buildledger.vendor.entity.VendorDocument;
import com.buildledger.vendor.enums.DocumentType;
import com.buildledger.vendor.enums.VendorStatus;
import com.buildledger.vendor.enums.VerificationStatus;
import com.buildledger.vendor.event.NotificationProducer;
import com.buildledger.vendor.exception.BadRequestException;
import com.buildledger.vendor.exception.DuplicateResourceException;
import com.buildledger.vendor.exception.ResourceNotFoundException;
import com.buildledger.vendor.exception.VendorException;
import com.buildledger.vendor.feign.ContractServiceClient;
import com.buildledger.vendor.feign.IamServiceClient;
import com.buildledger.vendor.repository.VendorDocumentRepository;
import com.buildledger.vendor.repository.VendorRepository;
import com.buildledger.vendor.storage.LocalFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendorServiceImplTest {

    @Mock private VendorRepository vendorRepository;
    @Mock private VendorDocumentRepository vendorDocumentRepository;
    @Mock private LocalFileStorageService fileStorageService;
    @Mock private IamServiceClient iamServiceClient;
    @Mock private ContractServiceClient contractServiceClient;
    @Mock private NotificationProducer notificationProducer;

    @InjectMocks
    private VendorServiceImpl vendorService;

    @BeforeEach
    void setUp() {
        // Inject @Value field — Spring context is not loaded in unit tests
        ReflectionTestUtils.setField(vendorService, "maxFileSizeMb", 10L);
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private Vendor buildVendor(Long id, VendorStatus status) {
        return Vendor.builder()
                .vendorId(id)
                .name("Test Vendor")
                .email("vendor@example.com")
                .phone("9876543210")
                .category("Technology")
                .address("123 Test Street")
                .username("testvendor")
                .passwordHash("$2a$12$hashedpassword")
                .status(status)
                .build();
    }

    private VendorDocument buildDocument(Long docId, Vendor vendor, VerificationStatus status) {
        return VendorDocument.builder()
                .documentId(docId)
                .vendor(vendor)
                .docType(DocumentType.PAN_CARD)
                .fileUri("uploads/vendor_1/123_test.pdf")
                .uploadedDate(LocalDate.now())
                .verificationStatus(status)
                .remarks("Test remarks")
                .build();
    }

    private MockMultipartFile buildValidPdfFile() {
        return new MockMultipartFile("file", "test.pdf", "application/pdf", "PDF content".getBytes());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // registerVendor
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("registerVendor — success: new vendor saved with PENDING status")
    void registerVendor_success() {
        // Arrange
        CreateVendorRequestDTO request = new CreateVendorRequestDTO();
        request.setName("Test Vendor");
        request.setEmail("vendor@example.com");
        request.setUsername("testvendor");
        request.setPassword("Test@1234");
        request.setCategory("Technology");

        Vendor savedVendor = buildVendor(1L, VendorStatus.PENDING);

        when(vendorRepository.existsByEmail("vendor@example.com")).thenReturn(false);
        when(vendorRepository.existsByUsername("testvendor")).thenReturn(false);
        when(vendorRepository.save(any(Vendor.class))).thenReturn(savedVendor);

        // Act
        VendorResponseDTO result = vendorService.registerVendor(request);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getVendorId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(VendorStatus.PENDING);
        verify(vendorRepository).save(any(Vendor.class));
    }

    @Test
    @DisplayName("registerVendor — duplicate email: throws DuplicateResourceException")
    void registerVendor_duplicateEmail_throwsException() {
        // Arrange
        CreateVendorRequestDTO request = new CreateVendorRequestDTO();
        request.setEmail("vendor@example.com");
        request.setUsername("testvendor");

        when(vendorRepository.existsByEmail("vendor@example.com")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> vendorService.registerVendor(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("email already registered");

        verify(vendorRepository, never()).save(any());
    }

    @Test
    @DisplayName("registerVendor — duplicate username: throws DuplicateResourceException")
    void registerVendor_duplicateUsername_throwsException() {
        // Arrange
        CreateVendorRequestDTO request = new CreateVendorRequestDTO();
        request.setEmail("new@example.com");
        request.setUsername("testvendor");

        when(vendorRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(vendorRepository.existsByUsername("testvendor")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> vendorService.registerVendor(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already taken");

        verify(vendorRepository, never()).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getVendorById
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getVendorById — success: returns vendor response DTO")
    void getVendorById_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));

        VendorResponseDTO result = vendorService.getVendorById(1L);

        assertThat(result.getVendorId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Vendor");
        assertThat(result.getStatus()).isEqualTo(VendorStatus.PENDING);
    }

    @Test
    @DisplayName("getVendorById — not found: throws ResourceNotFoundException")
    void getVendorById_notFound_throwsException() {
        when(vendorRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vendorService.getVendorById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vendor not found with id: 99");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getVendorByUsername
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getVendorByUsername — success: returns vendor matching username")
    void getVendorByUsername_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        when(vendorRepository.findByUsername("testvendor")).thenReturn(Optional.of(vendor));

        VendorResponseDTO result = vendorService.getVendorByUsername("testvendor");

        assertThat(result.getUsername()).isEqualTo("testvendor");
    }

    @Test
    @DisplayName("getVendorByUsername — not found: throws ResourceNotFoundException")
    void getVendorByUsername_notFound_throwsException() {
        when(vendorRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vendorService.getVendorByUsername("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getAllVendors / getVendorsByStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAllVendors — success: returns all vendors as a list")
    void getAllVendors_success() {
        when(vendorRepository.findAll()).thenReturn(
                List.of(buildVendor(1L, VendorStatus.PENDING), buildVendor(2L, VendorStatus.ACTIVE)));

        List<VendorResponseDTO> result = vendorService.getAllVendors();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getVendorsByStatus — success: filters vendors by status")
    void getVendorsByStatus_success() {
        when(vendorRepository.findByStatus(VendorStatus.PENDING))
                .thenReturn(List.of(buildVendor(1L, VendorStatus.PENDING)));

        List<VendorResponseDTO> result = vendorService.getVendorsByStatus(VendorStatus.PENDING);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(VendorStatus.PENDING);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateVendor
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("updateVendor — success: ACTIVE vendor fields updated")
    void updateVendor_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorRepository.save(vendor)).thenReturn(vendor);

        UpdateVendorRequestDTO request = new UpdateVendorRequestDTO();
        request.setName("Updated Name");
        request.setPhone("9123456789");

        VendorResponseDTO result = vendorService.updateVendor(1L, request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        verify(vendorRepository).save(vendor);
    }

    @Test
    @DisplayName("updateVendor — not ACTIVE: throws BadRequestException")
    void updateVendor_vendorNotActive_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));

        UpdateVendorRequestDTO request = new UpdateVendorRequestDTO();
        request.setName("Updated Name");

        assertThatThrownBy(() -> vendorService.updateVendor(1L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only ACTIVE vendors");
    }

    @Test
    @DisplayName("updateVendor — duplicate email: throws DuplicateResourceException")
    void updateVendor_newEmailAlreadyTaken_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorRepository.existsByEmail("taken@example.com")).thenReturn(true);

        UpdateVendorRequestDTO request = new UpdateVendorRequestDTO();
        request.setEmail("taken@example.com");

        assertThatThrownBy(() -> vendorService.updateVendor(1L, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Email already registered");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // deleteVendor
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("deleteVendor — PENDING vendor: deleted without contract check")
    void deleteVendor_pendingVendor_deletedSuccessfully() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.empty());

        vendorService.deleteVendor(1L);

        verify(vendorRepository).delete(vendor);
        // Contract service must NOT be called for non-ACTIVE vendors
        verify(contractServiceClient, never()).getContractsByVendor(any());
    }

    @Test
    @DisplayName("deleteVendor — ACTIVE vendor with no contracts: deleted successfully")
    void deleteVendor_activeVendorNoContracts_deletedSuccessfully() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(contractServiceClient.getContractsByVendor(1L)).thenReturn(
                ApiResponseDTO.<List<Map<String, Object>>>builder()
                        .success(true)
                        .data(Collections.emptyList())
                        .build());
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.empty());

        vendorService.deleteVendor(1L);

        verify(vendorRepository).delete(vendor);
    }

    @Test
    @DisplayName("deleteVendor — ACTIVE vendor with active contracts: throws VendorException")
    void deleteVendor_activeVendorWithContracts_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(contractServiceClient.getContractsByVendor(1L)).thenReturn(
                ApiResponseDTO.<List<Map<String, Object>>>builder()
                        .success(true)
                        .data(List.of(Map.of("contractId", "1")))
                        .build());

        assertThatThrownBy(() -> vendorService.deleteVendor(1L))
                .isInstanceOf(VendorException.class)
                .hasMessageContaining("Cannot delete vendor");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // uploadDocument
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("uploadDocument — success: document saved for PENDING vendor")
    void uploadDocument_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument savedDoc = buildDocument(10L, vendor, VerificationStatus.PENDING);
        MockMultipartFile file = buildValidPdfFile();

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.empty());
        when(fileStorageService.store(file, 1L)).thenReturn("uploads/vendor_1/test.pdf");
        when(vendorDocumentRepository.save(any(VendorDocument.class))).thenReturn(savedDoc);

        VendorDocumentResponseDTO result = vendorService.uploadDocument(
                1L, file, DocumentType.PAN_CARD, "Test remarks", "testvendor");

        assertThat(result).isNotNull();
        assertThat(result.getDocumentId()).isEqualTo(10L);
        assertThat(result.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        verify(vendorDocumentRepository).save(any(VendorDocument.class));
    }

    @Test
    @DisplayName("uploadDocument — vendor not PENDING: throws BadRequestException")
    void uploadDocument_vendorNotPending_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        MockMultipartFile file = buildValidPdfFile();

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));

        assertThatThrownBy(() -> vendorService.uploadDocument(
                1L, file, DocumentType.PAN_CARD, null, "testvendor"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only PENDING vendors");
    }

    @Test
    @DisplayName("uploadDocument — document already exists: throws BadRequestException")
    void uploadDocument_documentAlreadyExists_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument existingDoc = buildDocument(10L, vendor, VerificationStatus.PENDING);
        MockMultipartFile file = buildValidPdfFile();

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.of(existingDoc));

        assertThatThrownBy(() -> vendorService.uploadDocument(
                1L, file, DocumentType.PAN_CARD, null, "testvendor"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been uploaded");
    }

    @Test
    @DisplayName("uploadDocument — empty file: throws BadRequestException")
    void uploadDocument_emptyFile_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vendorService.uploadDocument(
                1L, emptyFile, DocumentType.PAN_CARD, null, "testvendor"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No file provided");
    }

    @Test
    @DisplayName("uploadDocument — non-PDF file: throws BadRequestException")
    void uploadDocument_nonPdfFile_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        MockMultipartFile jpgFile = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", "image content".getBytes());

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vendorService.uploadDocument(
                1L, jpgFile, DocumentType.PAN_CARD, null, "testvendor"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only PDF files");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getVendorDocument / getDocumentsByStatus / downloadDocument / getDocumentFileUrl
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getVendorDocument — success: returns document for vendor")
    void getVendorDocument_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument doc = buildDocument(10L, vendor, VerificationStatus.PENDING);

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.of(doc));

        VendorDocumentResponseDTO result = vendorService.getVendorDocument(1L);

        assertThat(result.getDocumentId()).isEqualTo(10L);
        assertThat(result.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    @DisplayName("getVendorDocument — document not found: throws ResourceNotFoundException")
    void getVendorDocument_notFound_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vendorService.getVendorDocument(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getDocumentsByStatus — success: returns documents matching given status")
    void getDocumentsByStatus_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument doc = buildDocument(10L, vendor, VerificationStatus.PENDING);

        when(vendorDocumentRepository.findByVerificationStatus(VerificationStatus.PENDING))
                .thenReturn(List.of(doc));

        List<VendorDocumentResponseDTO> result = vendorService.getDocumentsByStatus(VerificationStatus.PENDING);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    @DisplayName("downloadDocument — success: returns resource from file storage")
    void downloadDocument_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument doc = buildDocument(10L, vendor, VerificationStatus.PENDING);
        Resource mockResource = mock(Resource.class);

        when(vendorDocumentRepository.findById(10L)).thenReturn(Optional.of(doc));
        when(fileStorageService.load("uploads/vendor_1/123_test.pdf")).thenReturn(mockResource);

        Resource result = vendorService.downloadDocument(10L);

        assertThat(result).isEqualTo(mockResource);
    }

    @Test
    @DisplayName("getDocumentFileUrl — success: returns stored file URI")
    void getDocumentFileUrl_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument doc = buildDocument(10L, vendor, VerificationStatus.PENDING);

        when(vendorDocumentRepository.findById(10L)).thenReturn(Optional.of(doc));

        String result = vendorService.getDocumentFileUrl(10L);

        assertThat(result).isEqualTo("uploads/vendor_1/123_test.pdf");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // replaceDocument
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("replaceDocument — success: old file deleted and new document saved")
    void replaceDocument_success() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument existingDoc = buildDocument(10L, vendor, VerificationStatus.PENDING);
        MockMultipartFile file = buildValidPdfFile();

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.of(existingDoc));
        when(fileStorageService.store(file, 1L)).thenReturn("uploads/vendor_1/new_test.pdf");
        when(vendorDocumentRepository.save(existingDoc)).thenReturn(existingDoc);

        VendorDocumentResponseDTO result = vendorService.replaceDocument(
                1L, file, DocumentType.GST_CERTIFICATE, "New remarks");

        assertThat(result).isNotNull();
        verify(fileStorageService).delete("uploads/vendor_1/123_test.pdf");
        verify(vendorDocumentRepository).save(existingDoc);
    }

    @Test
    @DisplayName("replaceDocument — vendor not PENDING: throws BadRequestException")
    void replaceDocument_vendorNotPending_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        MockMultipartFile file = buildValidPdfFile();

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));

        assertThatThrownBy(() -> vendorService.replaceDocument(
                1L, file, DocumentType.PAN_CARD, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only PENDING vendors");
    }

    @Test
    @DisplayName("replaceDocument — document already APPROVED: throws BadRequestException")
    void replaceDocument_documentAlreadyReviewed_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument approvedDoc = buildDocument(10L, vendor, VerificationStatus.APPROVED);
        MockMultipartFile file = buildValidPdfFile();

        when(vendorRepository.findById(1L)).thenReturn(Optional.of(vendor));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.of(approvedDoc));

        assertThatThrownBy(() -> vendorService.replaceDocument(
                1L, file, DocumentType.PAN_CARD, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot replace a document");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // reviewDocument
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reviewDocument — APPROVED: vendor status auto-promoted to ACTIVE")
    void reviewDocument_approved_promotesVendorToActive() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument document = buildDocument(10L, vendor, VerificationStatus.PENDING);

        when(vendorDocumentRepository.findById(10L)).thenReturn(Optional.of(document));
        // The service mutates document.verificationStatus = APPROVED before calling save.
        // Using thenAnswer so the returned object reflects the mutation.
        when(vendorDocumentRepository.save(any(VendorDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // autoUpdateVendorStatus calls findByVendorVendorId — document is already APPROVED in memory
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.of(document));
        when(vendorRepository.save(any(Vendor.class))).thenReturn(vendor);
        when(iamServiceClient.createVendorUser(any(), any(), any(), any(), any()))
                .thenReturn(ApiResponseDTO.success("created", null));

        VendorDocumentResponseDTO result = vendorService.reviewDocument(
                10L, VerificationStatus.APPROVED, "Looks good", "admin");

        assertThat(result).isNotNull();
        // Vendor should have been promoted to ACTIVE
        assertThat(vendor.getStatus()).isEqualTo(VendorStatus.ACTIVE);
        verify(vendorRepository).save(vendor);
    }

    @Test
    @DisplayName("reviewDocument — REJECTED: vendor status set to SUSPENDED")
    void reviewDocument_rejected_suspendsVendor() {
        Vendor vendor = buildVendor(1L, VendorStatus.PENDING);
        VendorDocument document = buildDocument(10L, vendor, VerificationStatus.PENDING);

        when(vendorDocumentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(vendorDocumentRepository.save(any(VendorDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(vendorDocumentRepository.findByVendorVendorId(1L)).thenReturn(Optional.of(document));
        when(vendorRepository.save(any(Vendor.class))).thenReturn(vendor);

        VendorDocumentResponseDTO result = vendorService.reviewDocument(
                10L, VerificationStatus.REJECTED, "Document unclear", "admin");

        assertThat(result).isNotNull();
        // Vendor should have been suspended
        assertThat(vendor.getStatus()).isEqualTo(VendorStatus.SUSPENDED);
        verify(vendorRepository).save(vendor);
    }

    @Test
    @DisplayName("reviewDocument — status PENDING: throws BadRequestException")
    void reviewDocument_statusSetToPending_throwsException() {
        // Reviewers cannot set a document back to PENDING
        assertThatThrownBy(() -> vendorService.reviewDocument(
                10L, VerificationStatus.PENDING, null, "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must be APPROVED or REJECTED");
    }

    @Test
    @DisplayName("reviewDocument — already reviewed: throws BadRequestException")
    void reviewDocument_documentAlreadyReviewed_throwsException() {
        Vendor vendor = buildVendor(1L, VendorStatus.ACTIVE);
        VendorDocument approvedDoc = buildDocument(10L, vendor, VerificationStatus.APPROVED);

        when(vendorDocumentRepository.findById(10L)).thenReturn(Optional.of(approvedDoc));

        assertThatThrownBy(() -> vendorService.reviewDocument(
                10L, VerificationStatus.REJECTED, "Changed decision", "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already APPROVED");
    }
}
