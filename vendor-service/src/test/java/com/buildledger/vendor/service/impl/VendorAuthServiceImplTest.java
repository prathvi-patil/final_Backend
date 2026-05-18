package com.buildledger.vendor.service.impl;

import com.buildledger.vendor.dto.request.VendorLoginRequestDTO;
import com.buildledger.vendor.dto.response.VendorLoginResponseDTO;
import com.buildledger.vendor.entity.Vendor;
import com.buildledger.vendor.enums.VendorStatus;
import com.buildledger.vendor.exception.BadRequestException;
import com.buildledger.vendor.exception.ResourceNotFoundException;
import com.buildledger.vendor.repository.VendorRepository;
import com.buildledger.vendor.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendorAuthServiceImplTest {

    @Mock private VendorRepository vendorRepository;
    @Mock private JwtUtils jwtUtils;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private VendorAuthServiceImpl vendorAuthService;

    @BeforeEach
    void setUp() {
        // Inject @Value field — Spring context is not loaded in unit tests
        ReflectionTestUtils.setField(vendorAuthService, "jwtExpiration", 3600000L);
    }

    // ── Helper builder ────────────────────────────────────────────────────────

    private Vendor buildVendor(VendorStatus status) {
        return Vendor.builder()
                .vendorId(1L)
                .name("Test Vendor")
                .email("vendor@example.com")
                .username("testvendor")
                .passwordHash("$2a$12$hashedpassword")
                .status(status)
                .build();
    }

    private VendorLoginRequestDTO buildLoginRequest() {
        VendorLoginRequestDTO request = new VendorLoginRequestDTO();
        request.setUsername("testvendor");
        request.setPassword("Test@1234");
        return request;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // loginPendingVendor
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("loginPendingVendor — success: returns JWT token for PENDING vendor")
    void loginPendingVendor_success() {
        // Arrange
        Vendor vendor = buildVendor(VendorStatus.PENDING);
        VendorLoginRequestDTO request = buildLoginRequest();

        when(vendorRepository.findByUsername("testvendor")).thenReturn(Optional.of(vendor));
        when(passwordEncoder.matches("Test@1234", "$2a$12$hashedpassword")).thenReturn(true);
        when(jwtUtils.generateToken("testvendor", 1L, "PENDING")).thenReturn("mock.jwt.token");

        // Act
        VendorLoginResponseDTO result = vendorAuthService.loginPendingVendor(request);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getAccessToken()).isEqualTo("mock.jwt.token");
        assertThat(result.getTokenType()).isEqualTo("Bearer");
        assertThat(result.getVendorId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("testvendor");
        assertThat(result.getStatus()).isEqualTo(VendorStatus.PENDING);
        assertThat(result.getExpiresIn()).isEqualTo(3600L);  // 3600000ms / 1000
    }

    @Test
    @DisplayName("loginPendingVendor — vendor not found: throws ResourceNotFoundException")
    void loginPendingVendor_vendorNotFound_throwsException() {
        VendorLoginRequestDTO request = buildLoginRequest();
        request.setUsername("unknown");

        when(vendorRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vendorAuthService.loginPendingVendor(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vendor not found with username: unknown");
    }

    @Test
    @DisplayName("loginPendingVendor — vendor is ACTIVE (not PENDING): throws BadRequestException")
    void loginPendingVendor_vendorIsActive_throwsException() {
        // Only PENDING vendors can use this login endpoint
        Vendor activeVendor = buildVendor(VendorStatus.ACTIVE);
        VendorLoginRequestDTO request = buildLoginRequest();

        when(vendorRepository.findByUsername("testvendor")).thenReturn(Optional.of(activeVendor));

        assertThatThrownBy(() -> vendorAuthService.loginPendingVendor(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only vendors with PENDING status can login");
    }

    @Test
    @DisplayName("loginPendingVendor — vendor is SUSPENDED: throws BadRequestException")
    void loginPendingVendor_vendorIsSuspended_throwsException() {
        Vendor suspendedVendor = buildVendor(VendorStatus.SUSPENDED);
        VendorLoginRequestDTO request = buildLoginRequest();

        when(vendorRepository.findByUsername("testvendor")).thenReturn(Optional.of(suspendedVendor));

        assertThatThrownBy(() -> vendorAuthService.loginPendingVendor(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only vendors with PENDING status can login");
    }

    @Test
    @DisplayName("loginPendingVendor — wrong password: throws BadRequestException")
    void loginPendingVendor_wrongPassword_throwsException() {
        Vendor vendor = buildVendor(VendorStatus.PENDING);
        VendorLoginRequestDTO request = buildLoginRequest();
        request.setPassword("WrongPassword");

        when(vendorRepository.findByUsername("testvendor")).thenReturn(Optional.of(vendor));
        when(passwordEncoder.matches("WrongPassword", "$2a$12$hashedpassword")).thenReturn(false);

        assertThatThrownBy(() -> vendorAuthService.loginPendingVendor(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid username or password");
    }
}
