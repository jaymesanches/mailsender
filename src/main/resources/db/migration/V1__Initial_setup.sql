CREATE TABLE emails (
    id RAW(16) PRIMARY KEY,
    recipient VARCHAR2(255) NOT NULL,
    subject VARCHAR2(255),
    body CLOB,
    is_html NUMBER(1),
    status VARCHAR2(50),
    created_at TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE email_attachments (
    id RAW(16) PRIMARY KEY,
    name VARCHAR2(255) NOT NULL,
    content_type VARCHAR2(255),
    storage_path VARCHAR2(255),
    email_id RAW(16),
    CONSTRAINT fk_email_attachments_email_id FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
);
