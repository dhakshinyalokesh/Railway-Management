/**
 * Common Authentication and Helper Utilities for Railway System
 * Simple, beginner-friendly JavaScript with no dependencies.
 */

// Helper: Escape HTML to prevent XSS
function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[char]);
}

// Helper: Format minutes into HH:MM
function formatTime(minutes) {
  if (isNaN(minutes)) return '--:--';
  const hour = Math.floor(minutes / 60) % 24;
  const minute = minutes % 60;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

// Helper: Display toast notification
function showToast(message, isError = false) {
  let toast = document.querySelector('#toastNotice');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'toastNotice';
    toast.className = 'toast-notice';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.style.borderColor = isError ? 'var(--danger)' : 'var(--border-accent)';
  toast.classList.add('show');
  clearTimeout(window._toastTimeout);
  window._toastTimeout = setTimeout(() => {
    toast.classList.remove('show');
  }, 3200);
}

// Wire up password visibility toggle buttons
function initPasswordToggles() {
  document.querySelectorAll('.password-toggle-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const targetId = btn.getAttribute('data-target');
      const input = document.getElementById(targetId);
      if (!input) return;
      if (input.type === 'password') {
        input.type = 'text';
        btn.textContent = '🔒 Hide';
        btn.setAttribute('aria-label', 'Hide password');
      } else {
        input.type = 'password';
        btn.textContent = '👁 Show';
        btn.setAttribute('aria-label', 'Show password');
      }
    });
  });
}

// Client-side route protection: verifies login and role
async function checkAuth(allowedRoles = []) {
  try {
    const res = await fetch('/api/auth/me');
    if (!res.ok) {
      window.location.href = '/login.html';
      return null;
    }
    const data = await res.json();
    if (!data.authenticated || !data.user) {
      window.location.href = '/login.html';
      return null;
    }

    const user = data.user;

    // Check if role is allowed on this page
    if (allowedRoles.length > 0 && !allowedRoles.includes(user.role)) {
      // Redirect to correct dashboard
      if (user.role === 'ADMIN') {
        window.location.href = '/admin-dashboard.html';
      } else if (user.role === 'STAFF') {
        window.location.href = '/staff-dashboard.html';
      } else {
        window.location.href = '/passenger-dashboard.html';
      }
      return null;
    }

    // Populate user profile info in navbar
    const nameEl = document.querySelector('#headerUserName');
    if (nameEl) nameEl.textContent = user.fullName || user.username;

    const roleEl = document.querySelector('#headerUserRole');
    if (roleEl) {
      roleEl.textContent = user.role;
      roleEl.className = 'role-tag role-' + user.role.toLowerCase();
    }

    return user;
  } catch (err) {
    console.error('Auth verification error:', err);
    window.location.href = '/login.html';
    return null;
  }
}

// Logout handler
async function logout() {
  try {
    await fetch('/api/auth/logout', { method: 'POST' });
  } catch (ignored) {}
  window.location.href = '/login.html';
}

function initLogoutButton() {
  const logoutBtn = document.querySelector('#logoutBtn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', (e) => {
      e.preventDefault();
      logout();
    });
  }
}
