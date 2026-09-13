import crypto from 'crypto';
import http from 'http';

const PUBLIC_KEY_B64 = 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDm6u8q8X78onmbU4wGMq3b4ufbWE18YzuWo5jwkUlwWPTPcENbZYtveyTepp2Od1CTDcjhTUmVYvFkhaCF46UfOxZrwoSZc3jf3WXXd0hLOPBHuulynknj2KsWvKDuRig7J2o4KbDhyl0nnlUiMrIiD0tv1rBKpNCJ/T+MN0bERQIDAQAB';
const HEADER_FLAG = 'encrypt-key';

function encryptBody(plainBody) {
  const aesPassword = 'Panjia2026Test!!';
  const aesKeyBase64 = Buffer.from(aesPassword, 'utf8').toString('base64');

  const pemKey = `-----BEGIN PUBLIC KEY-----\n${PUBLIC_KEY_B64}\n-----END PUBLIC KEY-----`;
  const rsaPub = crypto.createPublicKey(pemKey);

  const encryptedKey = crypto.publicEncrypt(
    { key: rsaPub, padding: crypto.constants.RSA_PKCS1_PADDING },
    Buffer.from(aesKeyBase64, 'utf8')
  ).toString('base64');

  const cipher = crypto.createCipheriv('aes-128-ecb', Buffer.from(aesPassword, 'utf8'), null);
  const encryptedBody = Buffer.concat([
    cipher.update(plainBody, 'utf8'),
    cipher.final()
  ]).toString('base64');

  return { body: encryptedBody, headerValue: encryptedKey };
}

function login() {
  return new Promise((resolve, reject) => {
    const loginBody = JSON.stringify({
      username: 'admin',
      password: 'admin123',
      grantType: 'password',
      tenantId: '000000',
      rememberMe: false
    });
    const { body: encryptedBody, headerValue } = encryptBody(loginBody);

    const options = {
      hostname: 'localhost',
      port: 8080,
      path: '/auth/login',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'clientid': 'e5cd7e4891bf95d1d19206ce24a7b32e',
        [HEADER_FLAG]: headerValue,
      }
    };
    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch { resolve({ raw: data }); }
      });
    });
    req.on('error', reject);
    req.write(encryptedBody);
    req.end();
  });
}

const result = await login();
console.log(JSON.stringify(result, null, 2));
