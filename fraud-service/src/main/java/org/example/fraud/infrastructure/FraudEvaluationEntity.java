package org.example.fraud.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.fraud.domain.FraudDecision;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fraud_evaluation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FraudEvaluationEntity {

  @Id
  @Column(name = "evaluation_id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(name = "currency_code", nullable = false, updatable = false, length = 3)
  private String currencyCode;

  @Column(name = "merchant_reference")
  @Size(max = 128)
  private String merchantReference;

  @Column(name = "idempotency_key")
  @Size(max = 30)
  private String idempotencyKey;

  @Column(name = "request_hash")
  @Size(max = 64)
  private String requestHash;

  @Column(name = "risk_score", nullable = false)
  private int riskScore;

  @Column(name = "rules_version", nullable = false)
  private String rulesVersion;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private FraudDecision decision;

  @Column(name = "rule_result", columnDefinition = "jsonb", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private List<Map<String, Object>> ruleResult;

  @Column(name = "ip_address", columnDefinition = "inet")
  @JdbcTypeCode(SqlTypes.INET)
  private String ipAddress;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
}
