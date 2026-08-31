-- suporta a busca do expurgo: terminais mais antigos que a retencao
CREATE INDEX idx_emails_purge ON emails (status, created_at);
