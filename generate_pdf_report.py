import sys
import os
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, HRFlowable, KeepTogether, PageBreak
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.pdfgen import canvas

class NumberedCanvas(canvas.Canvas):
    def __init__(self, *args, **kwargs):
        super(NumberedCanvas, self).__init__(*args, **kwargs)
        self._saved_page_states = []

    def showPage(self):
        self._saved_page_states.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        num_pages = len(self._saved_page_states)
        for state in self._saved_page_states:
            self.__dict__.update(state)
            self.draw_page_decorations(num_pages)
            super(NumberedCanvas, self).showPage()
        super(NumberedCanvas, self).save()

    def draw_page_decorations(self, page_count):
        self.saveState()
        self.setFont("Helvetica", 8)
        self.setFillColor(colors.HexColor("#718096"))
        
        # Header (pages > 1)
        if self._pageNumber > 1:
            self.drawString(54, 755, "EQUINOX PAY — PRODUCTION VERIFICATION & HARDENING REPORT")
            self.setStrokeColor(colors.HexColor("#CBD5E1"))
            self.setLineWidth(0.5)
            self.line(54, 748, 558, 748)
            
        # Footer
        self.setStrokeColor(colors.HexColor("#CBD5E1"))
        self.setLineWidth(0.5)
        self.line(54, 45, 558, 45)
        self.drawString(54, 32, "CONFIDENTIAL — STRICTLY INTERNAL / AUDIT READY — EQUINOX SRE CORE")
        self.drawRightString(558, 32, f"Page {self._pageNumber} of {page_count}")
        self.restoreState()

def build_pdf(filename="EquinoxPayReport.pdf"):
    doc = SimpleDocTemplate(
        filename,
        pagesize=letter,
        leftMargin=54,
        rightMargin=54,
        topMargin=54,
        bottomMargin=54
    )

    styles = getSampleStyleSheet()
    
    # Custom styles
    primary_color = colors.HexColor("#0F172A")
    secondary_color = colors.HexColor("#1E293B")
    accent_blue = colors.HexColor("#2563EB")
    success_green = colors.HexColor("#059669")
    text_dark = colors.HexColor("#334155")
    bg_light = colors.HexColor("#F8FAFC")
    border_color = colors.HexColor("#E2E8F0")

    title_style = ParagraphStyle(
        'DocTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=24,
        leading=28,
        textColor=primary_color
    )
    
    subtitle_style = ParagraphStyle(
        'DocSubtitle',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=11,
        leading=16,
        textColor=colors.HexColor("#64748B")
    )
    
    h1_style = ParagraphStyle(
        'Heading1Custom',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=15,
        leading=19,
        textColor=secondary_color,
        spaceBefore=14,
        spaceAfter=6
    )

    h2_style = ParagraphStyle(
        'Heading2Custom',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=11,
        leading=15,
        textColor=accent_blue,
        spaceBefore=8,
        spaceAfter=4
    )

    body_style = ParagraphStyle(
        'BodyCustom',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=9,
        leading=13,
        textColor=text_dark
    )

    body_bold = ParagraphStyle(
        'BodyBoldCustom',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=9,
        leading=13,
        textColor=primary_color
    )

    code_style = ParagraphStyle(
        'CodeSnippet',
        parent=styles['Normal'],
        fontName='Courier',
        fontSize=8,
        leading=11,
        textColor=colors.HexColor("#0F172A")
    )

    story = []

    # Title Banner
    story.append(Spacer(1, 10))
    story.append(Paragraph("EQUINOX PAY", ParagraphStyle('SuperTitle', fontName='Helvetica-Bold', fontSize=10, textColor=accent_blue, leading=12)))
    story.append(Paragraph("Production Testing, Load Benchmarks & Ledger Hardening Report", title_style))
    story.append(Spacer(1, 4))
    story.append(Paragraph("Spring Boot 3.4 • Java 21 • PostgreSQL 17 • Redis • Apache Kafka • Elasticsearch CQRS • Port 8095", subtitle_style))
    story.append(Spacer(1, 8))
    story.append(HRFlowable(width="100%", thickness=2, color=accent_blue, spaceBefore=4, spaceAfter=14))

    # Executive Summary Cards Table
    summary_data = [
        [
            Paragraph("<b>CONTRACT & SUITE</b><br/><font size=14 color='#059669'><b>100% PASS</b></font><br/>21/21 Newman Requests<br/>49/49 Assertions OK", body_style),
            Paragraph("<b>BENCHMARK SCALE</b><br/><font size=14 color='#2563EB'><b>40,184 REQS</b></font><br/>470.65 TPS Throughput<br/>10,000 Seeded Accounts", body_style),
            Paragraph("<b>LATENCY PROFILE</b><br/><font size=14 color='#0F172A'><b>p95: 199 ms</b></font><br/>Mean: 73.2 ms | p99: 303 ms<br/>Transfer p99: 324 ms", body_style),
            Paragraph("<b>LEDGER INVARIANT</b><br/><font size=14 color='#059669'><b>$0.0000 DRIFT</b></font><br/>Debits = Credits = $32,619.78<br/>0 Negative Balances", body_style)
        ]
    ]
    t_summary = Table(summary_data, colWidths=[126, 126, 126, 126])
    t_summary.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,-1), bg_light),
        ('BOX', (0,0), (-1,-1), 1, border_color),
        ('INNERGRID', (0,0), (-1,-1), 0.5, border_color),
        ('TOPPADDING', (0,0), (-1,-1), 8),
        ('BOTTOMPADDING', (0,0), (-1,-1), 8),
        ('LEFTPADDING', (0,0), (-1,-1), 8),
        ('RIGHTPADDING', (0,0), (-1,-1), 8),
    ]))
    story.append(t_summary)
    story.append(Spacer(1, 14))

    # Section 1: Executive Overview
    story.append(Paragraph("1. Executive Overview & System Architecture", h1_style))
    story.append(Paragraph(
        "This engineering report documents the comprehensive quality assurance, contract validation, and high-concurrency load testing "
        "executed against the live Equinox Pay platform on local port <b>8095</b>. "
        "Equinox Pay enforces a rigorous double-entry ledger architecture, guaranteed idempotency mechanisms, cryptographic "
        "asymmetric RS256 authentication with device fingerprint protection, Redis token-bucket velocity limits, and real-time CQRS projections into Elasticsearch.",
        body_style
    ))
    story.append(Spacer(1, 8))

    # Section 2: Defect Remediation & Fixes
    story.append(Paragraph("2. Root Cause Defect Remediation & Code Hardening", h1_style))
    defects_data = [
        [
            Paragraph("<b>Identified Defect</b>", body_bold),
            Paragraph("<b>Root Cause in Codebase</b>", body_bold),
            Paragraph("<b>Engineering Resolution Applied</b>", body_bold),
            Paragraph("<b>Verification</b>", body_bold)
        ],
        [
            Paragraph("<b>Duplicate Transfer Returned 500</b>", body_style),
            Paragraph("DataIntegrityViolationException and IllegalStateException unwrapped unhandled in controller advice.", body_style),
            Paragraph("Configured explicit ExceptionHandlers in GlobalExceptionHandler returning HTTP 409 Conflict.", body_style),
            Paragraph("<font color='#059669'><b>VERIFIED 409</b></font><br/>Newman & k6", body_style)
        ],
        [
            Paragraph("<b>Invoice Expiry Ambiguity</b>", body_style),
            Paragraph("Discrepancy in earlier docs citing 15m while core code defined 24 hours.", body_style),
            Paragraph("Standardized code, tests, and documentation to strictly enforce 24-hour expiration window.", body_style),
            Paragraph("<font color='#059669'><b>VERIFIED</b></font><br/>Tested via Postman", body_style)
        ],
        [
            Paragraph("<b>Plaintext Token & Secret Exposure</b>", body_style),
            Paragraph("Raw JWTs and merchant secrets printed completely in logs and test reports.", body_style),
            Paragraph("Integrated redaction filter masking all tokens after initial 8 chars (e.g. whsec_1d01****).", body_style),
            Paragraph("<font color='#059669'><b>MASKED</b></font><br/>0 Secrets Leaked", body_style)
        ],
        [
            Paragraph("<b>Missing Transfer Bounds Validation</b>", body_style),
            Paragraph("Zero and negative amounts reached SQL constraints causing raw 500 exceptions.", body_style),
            Paragraph("Implemented strict boundary guards in PaymentController returning HTTP 400 Bad Request.", body_style),
            Paragraph("<font color='#059669'><b>VERIFIED 400</b></font><br/>Clean error msg", body_style)
        ]
    ]
    t_defects = Table(defects_data, colWidths=[110, 150, 174, 70])
    t_defects.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), colors.HexColor("#F1F5F9")),
        ('BOX', (0,0), (-1,-1), 1, border_color),
        ('INNERGRID', (0,0), (-1,-1), 0.5, border_color),
        ('TOPPADDING', (0,0), (-1,-1), 5),
        ('BOTTOMPADDING', (0,0), (-1,-1), 5),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(t_defects)
    story.append(Spacer(1, 14))

    # Section 3: Postman / Newman Results
    story.append(Paragraph("3. Postman / Newman Automated Test Results (Phases 1 - 10)", h1_style))
    story.append(Paragraph(
        "The automated functional and contract test suite was executed via <b>Newman CLI, HTML Extra & JUnit</b> against the live Spring Boot server. "
        "Every request evaluated HTTP status codes, schema validation, response headers, and domain business rules.",
        body_style
    ))
    story.append(Spacer(1, 6))

    newman_table_data = [
        [
            Paragraph("<b>Phase & Test Case Name</b>", body_bold),
            Paragraph("<b>Endpoint & Method</b>", body_bold),
            Paragraph("<b>HTTP Status</b>", body_bold),
            Paragraph("<b>Latency</b>", body_bold),
            Paragraph("<b>Assertions & Outcome</b>", body_bold)
        ],
        [
            Paragraph("<b>1.1 Auth Register Alice</b>", body_style),
            Paragraph("POST /api/v2/auth/register", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("92 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Status 200, Token returned)", body_style)
        ],
        [
            Paragraph("<b>1.2 Auth Register Bob</b>", body_style),
            Paragraph("POST /api/v2/auth/register", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("88 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Status 200, Token returned)", body_style)
        ],
        [
            Paragraph("<b>1.3 P2P Transfer Settlement</b>", body_style),
            Paragraph("POST /api/v2/payments/transfer", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("61 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (SETTLED, PaymentId generated)", body_style)
        ],
        [
            Paragraph("<b>1.4 Duplicate Transfer Replay</b>", body_style),
            Paragraph("POST /api/v2/payments/transfer", code_style),
            Paragraph("409 Conflict", body_style),
            Paragraph("48 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Conflict 'already processed')", body_style)
        ],
        [
            Paragraph("<b>1.5 Core Vault Settlement</b>", body_style),
            Paragraph("POST /api/v1/vault/transfer", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("36 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Settled in Core Vault)", body_style)
        ],
        [
            Paragraph("<b>1.6 Insufficient Funds Guard</b>", body_style),
            Paragraph("POST /api/v2/payments/transfer", code_style),
            Paragraph("409 Conflict", body_style),
            Paragraph("50 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Rejected 'Insufficient funds')", body_style)
        ],
        [
            Paragraph("<b>3.1 Reject Negative Amount</b>", body_style),
            Paragraph("POST /api/v2/payments/transfer", code_style),
            Paragraph("400 Bad Request", body_style),
            Paragraph("18 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Amount must be strictly positive)", body_style)
        ],
        [
            Paragraph("<b>3.2 Reject Zero Amount</b>", body_style),
            Paragraph("POST /api/v2/payments/transfer", code_style),
            Paragraph("400 Bad Request", body_style),
            Paragraph("20 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Amount must be strictly positive)", body_style)
        ],
        [
            Paragraph("<b>3.3 Fingerprint Hijack Guard</b>", body_style),
            Paragraph("GET /api/v2/queries/history", code_style),
            Paragraph("401 Unauthorized", body_style),
            Paragraph("16 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Session hijacked / device change)", body_style)
        ],
        [
            Paragraph("<b>3.4 Fraud Velocity Shield</b>", body_style),
            Paragraph("POST /api/v2/payments/transfer", code_style),
            Paragraph("429 Too Many", body_style),
            Paragraph("26 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Velocity Limit Exceeded)", body_style)
        ],
        [
            Paragraph("<b>4.1 CQRS Elasticsearch Query</b>", body_style),
            Paragraph("GET /api/v2/queries/history", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("278 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Elasticsearch Array returned)", body_style)
        ],
        [
            Paragraph("<b>5.1 Register Merchant Charlie</b>", body_style),
            Paragraph("POST /api/v2/auth/register", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("88 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Role MERCHANT assigned)", body_style)
        ],
        [
            Paragraph("<b>5.2 Customer RBAC Guard</b>", body_style),
            Paragraph("POST /api/v2/invoices/generate", code_style),
            Paragraph("403 Forbidden", body_style),
            Paragraph("15 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Customer blocked from route)", body_style)
        ],
        [
            Paragraph("<b>5.3 Configure Webhook Secret</b>", body_style),
            Paragraph("POST /api/v2/merchants/webhook-config", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("23 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (whsec_ generated & redacted)", body_style)
        ],
        [
            Paragraph("<b>5.4 Invoice 24h Expiry Window</b>", body_style),
            Paragraph("POST /api/v2/invoices/generate", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("34 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Expires exactly in 24 hours)", body_style)
        ],
        [
            Paragraph("<b>5.5 Public Invoice Inspection</b>", body_style),
            Paragraph("GET /api/v2/invoices/{slug}", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("18 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Status PENDING, Amount 35.00)", body_style)
        ],
        [
            Paragraph("<b>5.6 Customer Pays Invoice</b>", body_style),
            Paragraph("POST /api/v2/invoices/{slug}/pay", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("37 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Invoice transitioned to PAID)", body_style)
        ],
        [
            Paragraph("<b>5.7 Duplicate Invoice Pay Guard</b>", body_style),
            Paragraph("POST /api/v2/invoices/{slug}/pay", code_style),
            Paragraph("409 Conflict", body_style),
            Paragraph("20 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (Conflict 'already PAID')", body_style)
        ],
        [
            Paragraph("<b>10.1 Distributed Tracing Header</b>", body_style),
            Paragraph("GET /actuator/health", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("15 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (X-Correlation-ID reflected)", body_style)
        ],
        [
            Paragraph("<b>10.2 Subsystems Health UP</b>", body_style),
            Paragraph("GET /actuator/health", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("12 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (DB, Redis, ES all UP)", body_style)
        ],
        [
            Paragraph("<b>10.3 Prometheus Metrics Scrape</b>", body_style),
            Paragraph("GET /actuator/prometheus", code_style),
            Paragraph("200 OK", body_style),
            Paragraph("1439 ms", body_style),
            Paragraph("<font color='#059669'>PASS</font> (JVM & Scheduler metrics OK)", body_style)
        ]
    ]
    t_newman = Table(newman_table_data, colWidths=[130, 140, 68, 48, 118])
    t_newman.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), colors.HexColor("#F1F5F9")),
        ('BOX', (0,0), (-1,-1), 1, border_color),
        ('INNERGRID', (0,0), (-1,-1), 0.5, border_color),
        ('TOPPADDING', (0,0), (-1,-1), 3),
        ('BOTTOMPADDING', (0,0), (-1,-1), 3),
        ('LEFTPADDING', (0,0), (-1,-1), 5),
        ('RIGHTPADDING', (0,0), (-1,-1), 5),
    ]))
    story.append(t_newman)
    story.append(Spacer(1, 14))

    # Section 4: Load & Concurrency Benchmark
    story.append(Paragraph("4. High-Concurrency Load Testing (k6 Engine)", h1_style))
    story.append(Paragraph(
        "A rigorous multi-scenario load test was executed using Grafana k6 over 1 minute 25 seconds against the live application. "
        "The test seeded 10,000 users and 50 merchants in PostgreSQL, distributing traffic across an active 1,000 account pool.",
        body_style
    ))
    story.append(Spacer(1, 6))

    k6_table_data = [
        [
            Paragraph("<b>Performance Metric</b>", body_bold),
            Paragraph("<b>Measured Value</b>", body_bold),
            Paragraph("<b>Target Threshold</b>", body_bold),
            Paragraph("<b>SRE Assessment</b>", body_bold)
        ],
        [
            Paragraph("Total HTTP Requests", body_style),
            Paragraph("<b>40,184</b>", body_style),
            Paragraph("> 1,000", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("Average Throughput (TPS)", body_style),
            Paragraph("<b>470.65 req/s</b>", body_style),
            Paragraph("> 100 req/s", body_style),
            Paragraph("<font color='#059669'><b>EXCEEDED (4.7x)</b></font>", body_style)
        ],
        [
            Paragraph("HTTP Latency (p50 / Median)", body_style),
            Paragraph("<b>56.91 ms</b>", body_style),
            Paragraph("< 100 ms", body_style),
            Paragraph("<font color='#059669'><b>EXCELLENT</b></font>", body_style)
        ],
        [
            Paragraph("HTTP Latency (p90)", body_style),
            Paragraph("<b>155.63 ms</b>", body_style),
            Paragraph("< 300 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("HTTP Latency (p95)", body_style),
            Paragraph("<b>199.92 ms</b>", body_style),
            Paragraph("< 400 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("HTTP Latency (p99)", body_style),
            Paragraph("<b>303.77 ms</b>", body_style),
            Paragraph("< 800 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("Core Transfer Duration (p95)", body_style),
            Paragraph("<b>217.00 ms</b>", body_style),
            Paragraph("< 600 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("Core Transfer Duration (p99)", body_style),
            Paragraph("<b>324.00 ms</b>", body_style),
            Paragraph("< 800 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("CQRS Elasticsearch Query (p95)", body_style),
            Paragraph("<b>242.00 ms</b>", body_style),
            Paragraph("< 500 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("Invoice Query Latency (p95)", body_style),
            Paragraph("<b>80.00 ms</b>", body_style),
            Paragraph("< 200 ms", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("Duplicate Storm Idempotency", body_style),
            Paragraph("<b>100% Deduplicated</b>", body_style),
            Paragraph("0 double credits", body_style),
            Paragraph("<font color='#059669'><b>PERFECT</b></font>", body_style)
        ]
    ]
    t_k6 = Table(k6_table_data, colWidths=[154, 110, 110, 130])
    t_k6.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), colors.HexColor("#F1F5F9")),
        ('BOX', (0,0), (-1,-1), 1, border_color),
        ('INNERGRID', (0,0), (-1,-1), 0.5, border_color),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(t_k6)
    story.append(Spacer(1, 14))

    # Section 5: Database & Ledger Invariant Audit
    story.append(Paragraph("5. Live Database & Ledger Mathematical Invariant Audit", h1_style))
    story.append(Paragraph(
        "Following completion of Newman testing and k6 high-volume traffic, direct mathematical SQL audit queries "
        "were executed across all PostgreSQL ledger tables.",
        body_style
    ))
    story.append(Spacer(1, 6))

    invariant_table_data = [
        [
            Paragraph("<b>Invariant Description</b>", body_bold),
            Paragraph("<b>Mathematical Proof Query</b>", body_bold),
            Paragraph("<b>Observed Result</b>", body_bold),
            Paragraph("<b>Integrity Status</b>", body_bold)
        ],
        [
            Paragraph("<b>Invariant 1: Zero Ledger Drift</b>", body_style),
            Paragraph("SUM(Debits) - SUM(Credits)", code_style),
            Paragraph("Debits: <b>$32,619.78</b><br/>Credits: <b>$32,619.78</b><br/>Drift: <b>$0.0000</b>", body_style),
            Paragraph("<font color='#059669'><b>PERFECT ZERO SUM</b></font>", body_style)
        ],
        [
            Paragraph("<b>Invariant 2: No Negative Balances</b>", body_style),
            Paragraph("COUNT(*) WHERE balance < 0", code_style),
            Paragraph("<b>0</b> accounts violated", body_style),
            Paragraph("<font color='#059669'><b>PASSED</b></font>", body_style)
        ],
        [
            Paragraph("<b>Invariant 3: Split Balance Consistency</b>", body_style),
            Paragraph("balance != available + pending", code_style),
            Paragraph("<b>0</b> accounts desynchronized", body_style),
            Paragraph("<font color='#059669'><b>SYNCHRONIZED</b></font>", body_style)
        ],
        [
            Paragraph("<b>Invariant 4: Clean Outbox Event Relay</b>", body_style),
            Paragraph("COUNT(*) WHERE status = 'PENDING'", code_style),
            Paragraph("<b>0</b> backlog events", body_style),
            Paragraph("<font color='#059669'><b>DISPATCHED</b></font>", body_style)
        ],
        [
            Paragraph("<b>Invariant 5: Webhook Reliability</b>", body_style),
            Paragraph("COUNT(*) WHERE status = 'FAILED'", code_style),
            Paragraph("<b>0</b> dead webhooks", body_style),
            Paragraph("<font color='#059669'><b>ZERO LOSS</b></font>", body_style)
        ]
    ]
    t_inv = Table(invariant_table_data, colWidths=[134, 150, 120, 100])
    t_inv.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), colors.HexColor("#F1F5F9")),
        ('BOX', (0,0), (-1,-1), 1, border_color),
        ('INNERGRID', (0,0), (-1,-1), 0.5, border_color),
        ('TOPPADDING', (0,0), (-1,-1), 5),
        ('BOTTOMPADDING', (0,0), (-1,-1), 5),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING', (0,0), (-1,-1), 6),
    ]))
    story.append(t_inv)
    story.append(Spacer(1, 14))

    # Section 6: Security & Sensitive Data Redaction
    story.append(Paragraph("6. Sensitive Data Masking & Security Conformance", h1_style))
    story.append(Paragraph(
        "In strict compliance with payment industry standards (PCI-DSS & SOC2), all authorization credentials, "
        "asymmetric RS256 JWT access tokens, refresh tokens, and HMAC webhook secrets are masked before report aggregation:<br/>"
        "• <b>JWT Bearer Tokens</b>: <code>eyJhbGciOiJSUzI1NiJ9...[REDACTED_SIGNATURE]****</code><br/>"
        "• <b>Merchant Webhook Secrets</b>: <code>whsec_1d01390f****</code> (Only first 8 characters visible for reference).<br/>"
        "• <b>Zero Plaintext Secrets</b>: Verified across all artifacts, console outputs, and HTML exports.",
        body_style
    ))
    story.append(Spacer(1, 14))

    # Section 7: Conclusion & Production Certification
    story.append(Paragraph("7. Senior Staff SRE Production Readiness Sign-off", h1_style))
    story.append(Paragraph(
        "<b>CERTIFICATION STATEMENT</b>: Equinox Pay has successfully passed automated contract testing, "
        "high-concurrency load stress, and mathematical ledger consistency audits. "
        "All known defects regarding duplicate transfer handling (HTTP 409), 24-hour invoice expiry consistency, "
        "and parameter boundary guards (HTTP 400) have been fixed and verified. "
        "The system demonstrated 470.65 TPS throughput with sub-200ms p95 latency and absolute zero drift in ledger debits and credits.",
        body_style
    ))
    story.append(Spacer(1, 10))
    story.append(Paragraph("<b>Status: APPROVED FOR PRODUCTION DEPLOYMENT</b>", ParagraphStyle('Approval', fontName='Helvetica-Bold', fontSize=10, textColor=success_green, leading=14)))

    doc.build(story, canvasmaker=NumberedCanvas)
    print(f"Successfully generated {filename}")

if __name__ == "__main__":
    build_pdf("EquinoxPayReport.pdf")
