import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';

/**
 * Behavio = tool lokal tanpa auth (design.md §6.2). Guard selalu mengizinkan.
 */
export const authGuard = (_route?: ActivatedRouteSnapshot, _state?: RouterStateSnapshot) => {
  return true;
};
