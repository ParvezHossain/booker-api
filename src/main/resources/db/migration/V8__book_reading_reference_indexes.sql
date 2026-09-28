-- Complete foreign-key lookup coverage without rewriting existing book/progress data.
CREATE INDEX book_documents_creator ON book_documents(created_by);
CREATE INDEX reading_progress_document_book ON reading_progress(document_id, book_id);
CREATE INDEX reading_progress_operations_book_document ON reading_progress_operations(book_id, document_id);
