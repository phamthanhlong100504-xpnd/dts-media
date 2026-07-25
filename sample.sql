-- Table: content_grants
-- Service: lms-access
-- Entities mapped: content-grant
-- Engine: yugabyte
-- Mô tả: Bảng ACL flat trả lời câu hỏi nhị phân "object X có quyền chạm 1 NODE trong cây học liệu trong cửa sổ thời gian nào không?". Mỗi row gắn 1 đối tượng được cấp (`object_code`) với 1 NODE (`content_code`) trong cửa sổ `start_time`/`end_time`. Bảng KHÔNG phân biệt mức độ tác động (`view`/`interact`/`submit`/`discuss`/`download`): chỉ trả lời "có quyền chạm hay không"; mức độ cụ thể do service khác (role/tier) quyết.
--
-- ĐỊNH DANH OBJECT qua 1 cột `object_code`: materialized path = chuỗi id nối bằng ":" (vd `<uuid>` cho 1 user, hoặc `<orgID>:<uuid>` cho nhiều cấp), KHÔNG có nhãn loại. KHÔNG có FK, KHÔNG ràng buộc enum. Runtime Check: caller LIỆT KÊ tập `object_code` chủ thể liên quan tới mình (user + các organization thuộc về + các role + các group — chỉ caller biết membership), KHÔNG wildcard; SERVICE `lms-access` tự expand ancestor plain-prefix của MỖI `object_code` rồi query `object_code IN (...)`.
--
-- ĐỊNH DANH NODE qua 1 cột `content_code`: mã phân cấp dạng `id1:id2:id3` (materialized path, các id nối bằng ":"). Cấp cả nhánh = lưu code node gốc (vd `id1`), KHÔNG dùng wildcard. KHÔNG pin theo version → khi tác giả publish version mới, grant tự động áp dụng. 2 cột phụ trợ `content_type`/`content_id` (nullable) chỉ phục vụ filter/audit ngược theo entity, KHÔNG tham gia hot-path Check.
--
-- INHERITANCE qua cây học liệu (TRÊN → XUỐNG): rule ở node cha tự áp xuống mọi node con. Check tại runtime KHÔNG dùng LIKE: SERVICE `lms-access` tự expand mỗi `content_code` được hỏi thành toàn bộ ancestor plain-prefix (vd `id1:id2:id3` → `id1`, `id1:id2`, `id1:id2:id3`) rồi query `content_code IN (...)` (so khớp HOÀN TOÀN). Caller truyền code THÔ (không tự expand, không wildcard).
--
-- LIFECYCLE: revoke grant bằng cách set `status='REVOKED'` (kèm lý do tùy chọn ở `revoke_reason`, actor ở `revoked_by`, thời điểm ở `revoked_at`). Bất biến "1 active grant cho mỗi tổ hợp (tenant, object_code, content_code)" qua partial unique index theo `status='ACTIVE'`.
--
-- AUDIT: `created_by`/`created_at` ghi "ai/khi nào cấp"; `revoked_by`/`revoked_at` ghi "ai/khi nào thu hồi" (đối xứng, chỉ có giá trị khi `status='REVOKED'`). KHÔNG dùng `updated_by` chung vì Upsert (gia hạn) cũng update row.

CREATE TABLE content_grants (
    id                  UUID         NOT NULL, -- Khóa chính uuidv7 (sortable theo thời gian; giảm hot-spot khi insert tốc độ cao).
    tenant_id           uuid        NOT NULL, -- Tenant sở hữu grant; multi-tenant isolation; mọi query scope theo `tenant_id`.
    object_code         text        NOT NULL, -- Mã đối tượng được cấp — materialized path (chuỗi id nối bằng ":", KHÔNG có nhãn loại; vd `<uuid>` hoặc `<orgID>:<uuid>`); KHÔNG có FK, KHÔNG ràng buộc enum; runtime Check query `object_code IN (...)`.
    content_code        text        NOT NULL, -- Mã NODE phân cấp dạng `id1:id2:id3` (materialized path); cấp cả nhánh = lưu code node gốc `id1` (KHÔNG dùng wildcard). Inheritance qua so khớp HOÀN TOÀN: service `lms-access` tự expand `content_code` được hỏi thành ancestor plain-prefix rồi query `content_code IN (...)` (KHÔNG dùng LIKE). KHÔNG pin version → grant tự áp khi version mới publish.
    content_type        text, -- (nullable) Loại NODE — phụ trợ filter/audit ngược theo entity; KHÔNG tham gia hot-path Check; KHÔNG ràng buộc enum.
    content_id          uuid, -- (nullable) Entity id gốc của NODE — phụ trợ filter/audit ngược theo entity; cross-service ref tới `lms-content-builder`/`lms-assignment`, KHÔNG có FK; có thể NULL với grant áp cả nhánh (lưu code node gốc, vd `id1`; KHÔNG dùng wildcard).
    start_time          timestamptz NOT NULL, -- Thời điểm bắt đầu hiệu lực của grant; cho phép schedule trước (vd khóa học mở 1/6).
    end_time            timestamptz, -- Thời điểm hết hiệu lực; NULL = vĩnh viễn; có giá trị = grant có hạn.
    status              text        NOT NULL DEFAULT 'ACTIVE', -- Trạng thái hiện tại (`ACTIVE`/`REVOKED`/`EXPIRED`); fast filter khi Check.
    revoke_reason       text, -- (nullable) Lý do thu hồi grant; chỉ có giá trị khi `status='REVOKED'`; phục vụ audit "vì sao gỡ quyền".
    revoked_by          uuid, -- (nullable) Actor thu hồi grant (admin/instructor/system); cross-service ref tới `identity`, KHÔNG có FK; chỉ có giá trị khi `status='REVOKED'`; phục vụ audit "ai gỡ quyền".
    revoked_at          timestamptz, -- (nullable) Thời điểm thu hồi grant; chỉ có giá trị khi `status='REVOKED'`; phục vụ audit "khi nào gỡ quyền" (tách biệt `updated_at` vốn đổi theo mọi lần ghi).
    metadata            jsonb       NOT NULL DEFAULT '{}'::jsonb, -- Metadata mở rộng (JSON) — lưu các thuộc tính ad-hoc/tùy biến không thuộc schema chính (vd: tag, custom field từ tích hợp ngoài, context bổ sung); không tham gia logic Check chuẩn.
    created_by          uuid        NOT NULL, -- Actor tạo grant (admin/instructor/system); cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit "ai cấp quyền".
    created_at          timestamptz NOT NULL DEFAULT NOW(), -- Thời điểm bản ghi được tạo.
    updated_at          timestamptz NOT NULL DEFAULT NOW() -- Thời điểm bản ghi được cập nhật gần nhất.
);

ALTER TABLE content_grants
    ADD CONSTRAINT pk_content_grants PRIMARY KEY (id),
    ADD CONSTRAINT ck_content_grants_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    ADD CONSTRAINT ck_content_grants_time_window CHECK (end_time IS NULL OR end_time > start_time);

COMMENT ON COLUMN content_grants.id IS 'Khóa chính uuidv7 (sortable theo thời gian; giảm hot-spot khi insert tốc độ cao).';
COMMENT ON COLUMN content_grants.tenant_id IS 'Tenant sở hữu grant; multi-tenant isolation; mọi query scope theo `tenant_id`.';
COMMENT ON COLUMN content_grants.object_code IS 'Mã đối tượng được cấp — materialized path (chuỗi id nối bằng ":", KHÔNG có nhãn loại; vd `<uuid>` hoặc `<orgID>:<uuid>`); KHÔNG có FK, KHÔNG ràng buộc enum; runtime Check query `object_code IN (...)`.';
COMMENT ON COLUMN content_grants.content_code IS 'Mã NODE phân cấp dạng `id1:id2:id3` (materialized path); cấp cả nhánh = lưu code node gốc `id1` (KHÔNG dùng wildcard). Inheritance qua so khớp HOÀN TOÀN: service `lms-access` tự expand `content_code` được hỏi thành ancestor plain-prefix rồi query `content_code IN (...)` (KHÔNG dùng LIKE). KHÔNG pin version → grant tự áp khi version mới publish.';
COMMENT ON COLUMN content_grants.content_type IS '(nullable) Loại NODE — phụ trợ filter/audit ngược theo entity; KHÔNG tham gia hot-path Check; KHÔNG ràng buộc enum.';
COMMENT ON COLUMN content_grants.content_id IS '(nullable) Entity id gốc của NODE — phụ trợ filter/audit ngược theo entity; cross-service ref tới `lms-content-builder`/`lms-assignment`, KHÔNG có FK; có thể NULL với grant áp cả nhánh (lưu code node gốc, vd `id1`; KHÔNG dùng wildcard).';
COMMENT ON COLUMN content_grants.start_time IS 'Thời điểm bắt đầu hiệu lực của grant; cho phép schedule trước (vd khóa học mở 1/6).';
COMMENT ON COLUMN content_grants.end_time IS 'Thời điểm hết hiệu lực; NULL = vĩnh viễn; có giá trị = grant có hạn.';
COMMENT ON COLUMN content_grants.status IS 'Trạng thái hiện tại (`ACTIVE`/`REVOKED`/`EXPIRED`); fast filter khi Check.';
COMMENT ON COLUMN content_grants.revoke_reason IS '(nullable) Lý do thu hồi grant; chỉ có giá trị khi `status=''REVOKED''`; phục vụ audit "vì sao gỡ quyền".';
COMMENT ON COLUMN content_grants.revoked_by IS '(nullable) Actor thu hồi grant (admin/instructor/system); cross-service ref tới `identity`, KHÔNG có FK; chỉ có giá trị khi `status=''REVOKED''`; phục vụ audit "ai gỡ quyền".';
COMMENT ON COLUMN content_grants.revoked_at IS '(nullable) Thời điểm thu hồi grant; chỉ có giá trị khi `status=''REVOKED''`; phục vụ audit "khi nào gỡ quyền" (tách biệt `updated_at`).';
COMMENT ON COLUMN content_grants.metadata IS 'Metadata mở rộng (JSON) — lưu các thuộc tính ad-hoc/tùy biến không thuộc schema chính (vd: tag, custom field từ tích hợp ngoài, context bổ sung); không tham gia logic Check chuẩn.';
COMMENT ON COLUMN content_grants.created_by IS 'Actor tạo grant (admin/instructor/system); cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit "ai cấp quyền".';
COMMENT ON COLUMN content_grants.created_at IS 'Thời điểm bản ghi được tạo.';
COMMENT ON COLUMN content_grants.updated_at IS 'Thời điểm bản ghi được cập nhật gần nhất.';


-- Bất biến: 1 active grant cho mỗi tổ hợp (tenant, object_code, content_code)
CREATE UNIQUE INDEX uq_content_grants_active ON content_grants (tenant_id, object_code, content_code) WHERE status = 'ACTIVE';
-- Hot-path Check: service expand ancestor plain-prefix của (object_codes, content_codes) caller đưa → query `object_code IN (...) AND content_code IN (...)` (so khớp HOÀN TOÀN, KHÔNG LIKE, KHÔNG wildcard)
CREATE INDEX ix_content_grants_check ON content_grants (tenant_id, object_code, content_code, status);
-- Quản trị ngược: grant nào đang gắn vào 1 content cụ thể (audit/report theo entity)
CREATE INDEX ix_content_grants_content ON content_grants (tenant_id, content_type, content_id, status);
-- Scheduler quét grant hết hạn để chuyển status sang `expired`
CREATE INDEX ix_content_grants_expiry ON content_grants (status, end_time);
-- Liệt kê các grant của 1 object (vd "quyền của user X" trong UI quản trị)
CREATE INDEX ix_content_grants_object ON content_grants (tenant_id, object_code, status);
