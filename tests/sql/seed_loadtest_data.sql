-- ==============================================================================
-- EQUINOX PAY - SEED SCRIPT (10,000 USERS, 50 MERCHANTS, 10,000 ACCOUNTS)
-- ==============================================================================

DO $$
DECLARE
    v_batch_size INT := 1000;
    v_user_count INT := 10000;
    v_merchant_count INT := 50;
    v_i INT;
    v_now TIMESTAMP WITH TIME ZONE := clock_timestamp();
BEGIN
    RAISE NOTICE 'Starting batch generation of 10,000 accounts and users...';

    -- 1. Generate 10,000 customer accounts with opening balance of $1,000.00
    INSERT INTO accounts (id, balance, available_balance, pending_balance)
    SELECT 
        gen_random_uuid(),
        1000.0000,
        1000.0000,
        0.0000
    FROM generate_series(1, v_user_count)
    ON CONFLICT (id) DO NOTHING;

    -- 2. Generate 10,000 corresponding customer users linked to those accounts
    -- Using a deterministic subquery match for unlinked accounts
    INSERT INTO users (id, username, password, role, account_id)
    SELECT 
        gen_random_uuid(),
        'load_user_' || row_number() OVER (),
        '$2a$10$wT8mQxX7R6j8qUoI4pZZ7eYv.pS3R8oF7q0n4yP2F8rX0a0A2pM3m', -- Bcrypt 'Password123!'
        'USER',
        a.id
    FROM accounts a
    LEFT JOIN users u ON a.id = u.account_id
    WHERE u.id IS NULL AND a.id NOT IN (
        '00000000-0000-0000-0000-000000000000',
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222'
    )
    LIMIT v_user_count
    ON CONFLICT (username) DO NOTHING;

    -- 3. Generate 50 merchant accounts with $0 initial balance
    INSERT INTO accounts (id, balance, available_balance, pending_balance)
    SELECT 
        gen_random_uuid(),
        0.0000,
        0.0000,
        0.0000
    FROM generate_series(1, v_merchant_count)
    ON CONFLICT (id) DO NOTHING;

    -- 4. Generate 50 merchant users with webhook URLs and secrets
    INSERT INTO users (id, username, password, role, account_id, webhook_url, webhook_secret)
    SELECT 
        gen_random_uuid(),
        'load_merchant_' || row_number() OVER (),
        '$2a$10$wT8mQxX7R6j8qUoI4pZZ7eYv.pS3R8oF7q0n4yP2F8rX0a0A2pM3m',
        'MERCHANT',
        a.id,
        'https://loadtest-webhook.local/api/' || row_number() OVER (),
        'whsec_' || md5(random()::text)
    FROM accounts a
    LEFT JOIN users u ON a.id = u.account_id
    WHERE u.id IS NULL AND a.balance = 0.0000 AND a.id NOT IN (
        '00000000-0000-0000-0000-000000000000',
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222'
    )
    LIMIT v_merchant_count
    ON CONFLICT (username) DO NOTHING;

    -- 5. Seed Merchant Fee Plans (2.9% + $0.30)
    INSERT INTO merchant_fee_plans (id, merchant_account_id, percentage_rate, fixed_fee, created_at, updated_at)
    SELECT 
        gen_random_uuid(),
        u.account_id,
        0.0290,
        0.3000,
        v_now,
        v_now
    FROM users u
    WHERE u.role = 'MERCHANT'
    ON CONFLICT (merchant_account_id) DO NOTHING;

    RAISE NOTICE 'Seeding completed successfully!';
END $$;
