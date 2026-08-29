ALTER TABLE emails ADD COLUMN sent_by_account VARCHAR(100);

-- suporta a contagem diaria por conta (limite de ~10.000 destinatarios/dia por caixa)
CREATE INDEX idx_emails_account_sent ON emails (sent_by_account, sent_at);
